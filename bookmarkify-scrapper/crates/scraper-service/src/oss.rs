use std::collections::HashMap;

use sha2::{Digest, Sha256};

use crate::scraper::ScrapeError;

pub struct OssClient {
    key_id: String,
    key_secret: String,
    endpoint: String,
    bucket: String,
    pub base_url: String,
}

impl OssClient {
    pub fn from_env() -> Option<Self> {
        let key_id = std::env::var("OSS_ACCESS_KEY_ID").ok()?;
        let key_secret = std::env::var("OSS_ACCESS_KEY_SECRET").ok()?;
        let bucket = std::env::var("OSS_BUCKET").ok()?;
        let endpoint = std::env::var("OSS_ENDPOINT").ok()?;
        let base_url = std::env::var("OSS_BASE_URL").ok()?;
        Some(Self { key_id, key_secret, endpoint, bucket, base_url })
    }

    fn oss(&self) -> oss_rust_sdk::oss::OSS<'_> {
        // NOTE: oss-rust-sdk creates its own reqwest::Client internally, so
        // PROXY_URL and REQUEST_TIMEOUT_SECS do not apply to OSS uploads.
        oss_rust_sdk::oss::OSS::new(
            self.key_id.clone(),
            self.key_secret.clone(),
            self.endpoint.clone(),
            self.bucket.clone(),
        )
    }

    pub fn screenshot_key(page_url: &str) -> String {
        let hash = hex::encode(Sha256::digest(page_url.as_bytes()));
        format!("screenshots/{hash}.png")
    }

    pub fn asset_key(asset_url: &str, content_type: Option<&str>) -> String {
        let hash = hex::encode(Sha256::digest(asset_url.as_bytes()));
        let ext = ext_from_content_type(content_type.unwrap_or(""));
        format!("images/{hash}.{ext}")
    }

    /// Uploads bytes to OSS at `key`. Keys are derived from the source URL (SHA-256),
    /// so the same source URL always maps to the same OSS key. PUT is unconditional —
    /// no deduplication check is performed (oss-rust-sdk 0.3 has no HEAD API).
    /// Returns the full public URL on success.
    async fn upload_bytes(
        &self,
        key: &str,
        bytes: &[u8],
        content_type: &str,
    ) -> Result<String, ScrapeError> {
        let oss = self.oss();

        let mut put_headers: HashMap<String, String> = HashMap::new();
        put_headers.insert("Content-Type".to_string(), content_type.to_string());

        let response_body = oss
            .async_put_object_from_buffer(
                bytes,
                key,
                Some(put_headers),
                None::<HashMap<String, Option<String>>>,
            )
            .await
            .map_err(|e| ScrapeError::OssFailed(e.to_string()))?;

        // OSS returns XML error body on 4xx/5xx; empty body means success
        if !response_body.is_empty() {
            let body_str = String::from_utf8_lossy(&response_body);
            if body_str.contains("<Error>") || body_str.contains("<Code>") {
                return Err(ScrapeError::OssFailed(format!("OSS PUT failed: {body_str}")));
            }
        }

        Ok(format!("{}/{}", self.base_url, key))
    }

    /// Uploads OG image, logo, and screenshot bytes to OSS concurrently.
    /// Favicon is never uploaded to OSS — it is always fetched and returned as a base64 data URL.
    /// `page_url` is the original scraped URL — used as the key seed for the screenshot.
    /// Returns a modified `ScrapeResult` with OSS URLs replacing original values.
    pub async fn upload_assets(
        &self,
        mut result: crate::scraper::ScrapeResult,
        page_url: &str,
        http: &reqwest::Client,
    ) -> Result<crate::scraper::ScrapeResult, crate::scraper::ScrapeError> {
        let screenshot_bytes = result.screenshot_bytes.take(); // removes bytes from result
        let image_url = result.image.clone();
        let logo_url = result.logo.clone();
        let favicon_url = result.favicon.clone();

        let screenshot_key = Self::screenshot_key(page_url);
        let screenshot_fut = async {
            match screenshot_bytes {
                Some(bytes) => {
                    self.upload_bytes(&screenshot_key, &bytes, "image/png")
                        .await
                        .map(Some)
                }
                None => Ok(None),
            }
        };

        let (screenshot_result, image_result, logo_result, favicon_result) = tokio::join!(
            screenshot_fut,
            self.upload_url_asset(image_url.as_deref(), http),
            self.upload_url_asset(logo_url.as_deref(), http),
            Self::fetch_as_base64(favicon_url.as_deref(), http),
        );

        result.screenshot_url = screenshot_result?;
        result.image = image_result?;
        result.logo = logo_result?;
        result.favicon = favicon_result?;

        Ok(result)
    }

    /// Downloads the image at `url` and returns it as a base64 data URL (`data:<mime>;base64,...`).
    /// Returns `None` if `url` is `None`; `Some(data_url)` on success.
    async fn fetch_as_base64(
        url: Option<&str>,
        http: &reqwest::Client,
    ) -> Result<Option<String>, ScrapeError> {
        let url = match url {
            Some(u) => u,
            None => return Ok(None),
        };

        let referer = reqwest::Url::parse(url)
            .ok()
            .map(|u| format!("{}://{}", u.scheme(), u.host_str().unwrap_or("")))
            .unwrap_or_default();

        let response = http
            .get(url)
            .header("Referer", &referer)
            .send()
            .await
            .map_err(|e| ScrapeError::OssFailed(format!("favicon download failed: {e}")))?
            .error_for_status()
            .map_err(|e| ScrapeError::OssFailed(format!("favicon download failed: {e}")))?;

        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("image/png")
            .to_string();

        let bytes = response
            .bytes()
            .await
            .map_err(|e| ScrapeError::OssFailed(format!("favicon read failed: {e}")))?;

        use base64::{engine::general_purpose::STANDARD, Engine};
        let b64 = STANDARD.encode(&bytes);
        Ok(Some(format!("data:{content_type};base64,{b64}")))
    }

    /// Downloads the image at `url` (with a Referer header to bypass hotlink protection),
    /// then uploads to OSS. Returns `None` if `url` is `None`; `Some(oss_url)` on success.
    pub async fn upload_url_asset(
        &self,
        url: Option<&str>,
        http: &reqwest::Client,
    ) -> Result<Option<String>, ScrapeError> {
        let url = match url {
            Some(u) => u,
            None => return Ok(None),
        };

        // Derive Referer from the URL's origin to bypass simple hotlink checks
        let referer = reqwest::Url::parse(url)
            .ok()
            .map(|u| format!("{}://{}", u.scheme(), u.host_str().unwrap_or("")))
            .unwrap_or_default();

        let response = http
            .get(url)
            .header("Referer", &referer)
            .send()
            .await
            .map_err(|e| ScrapeError::OssFailed(format!("image download failed: {e}")))?
            .error_for_status()
            .map_err(|e| ScrapeError::OssFailed(format!("image download failed: {e}")))?;

        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("image/png")
            .to_string();

        // Reject non-image responses (e.g. HTML login walls that return 200)
        if !content_type.starts_with("image/") {
            return Err(ScrapeError::OssFailed(format!(
                "unexpected content type for asset {url}: {content_type}"
            )));
        }

        let bytes = response
            .bytes()
            .await
            .map_err(|e| ScrapeError::OssFailed(format!("image read failed: {e}")))?;

        let key = Self::asset_key(url, Some(&content_type));
        let oss_url = self.upload_bytes(&key, &bytes, &content_type).await?;
        Ok(Some(oss_url))
    }
}

fn ext_from_content_type(ct: &str) -> &str {
    match ct.split(';').next().unwrap_or("").trim() {
        "image/jpeg" | "image/jpg" => "jpg",
        "image/gif" => "gif",
        "image/webp" => "webp",
        "image/svg+xml" => "svg",
        "image/x-icon" | "image/vnd.microsoft.icon" => "ico",
        _ => "png",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn screenshot_key_is_sha256_of_page_url() {
        let url = "https://example.com/page";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(OssClient::screenshot_key(url), format!("screenshots/{hash}.png"));
    }

    #[test]
    fn asset_key_defaults_to_png() {
        let url = "https://example.com/logo";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(OssClient::asset_key(url, None), format!("images/{hash}.png"));
    }

    #[test]
    fn asset_key_uses_jpeg_content_type() {
        let url = "https://example.com/photo";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, Some("image/jpeg")),
            format!("images/{hash}.jpg")
        );
    }

    #[test]
    fn asset_key_uses_webp_content_type() {
        let url = "https://cdn.example.com/img";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, Some("image/webp")),
            format!("images/{hash}.webp")
        );
    }

    #[test]
    fn from_env_returns_none_when_vars_missing() {
        std::env::remove_var("OSS_ACCESS_KEY_ID");
        std::env::remove_var("OSS_ACCESS_KEY_SECRET");
        std::env::remove_var("OSS_BUCKET");
        std::env::remove_var("OSS_ENDPOINT");
        std::env::remove_var("OSS_BASE_URL");
        assert!(OssClient::from_env().is_none());
    }
}
