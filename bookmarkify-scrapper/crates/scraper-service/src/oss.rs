use hmac::{Hmac, Mac};
use sha1::Sha1;
use sha2::{Digest, Sha256};

use crate::scraper::ScrapeError;

/// Common OSS key prefix for all scrapper-produced assets. A single bucket
/// lifecycle rule on this prefix can later be used to auto-expire temporary images.
const OSS_PREFIX: &str = "bookmarkify/scrapper";

/// Upload-specific timeout — generous because screenshots/images can be up to
/// MAX_IMAGE_BYTES (10MB) and OSS is a separate leg from the page scrape itself.
const UPLOAD_TIMEOUT_SECS: u64 = 30;


/// Aliyun OSS V1 (HMAC-SHA1) request signature, base64-encoded.
/// See https://help.aliyun.com/document_detail/31951.html — `Authorization: OSS
/// <AccessKeyId>:<signature>` where `signature = base64(hmac_sha1(secret, string_to_sign))`.
fn sign_hmac_sha1_base64(secret: &str, string_to_sign: &str) -> String {
    let mut mac = Hmac::<Sha1>::new_from_slice(secret.as_bytes())
        .expect("HMAC accepts a key of any length");
    mac.update(string_to_sign.as_bytes());
    use base64::{engine::general_purpose::STANDARD, Engine};
    STANDARD.encode(mac.finalize().into_bytes())
}

pub struct OssClient {
    key_id: String,
    key_secret: String,
    endpoint: String,
    bucket: String,
    pub base_url: String,
    /// Dedicated client for OSS PUTs: longer timeout than the page-scrape client
    /// (uploads can be several MB) and — unlike the removed oss-rust-sdk, which built
    /// its own client with no timeout and no proxy support — honors `PROXY_URL`.
    http: reqwest::Client,
}

impl OssClient {
    pub fn from_env() -> Option<Self> {
        let key_id = std::env::var("OSS_ACCESS_KEY_ID").ok()?;
        let key_secret = std::env::var("OSS_ACCESS_KEY_SECRET").ok()?;
        let bucket = std::env::var("OSS_BUCKET").ok()?;
        let endpoint = std::env::var("OSS_ENDPOINT").ok()?;
        let base_url = std::env::var("OSS_BASE_URL").ok()?;

        let mut builder = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(UPLOAD_TIMEOUT_SECS));
        if let Some(proxy_url) = std::env::var("PROXY_URL").ok().filter(|s| !s.is_empty()) {
            match reqwest::Proxy::all(&proxy_url) {
                Ok(proxy) => builder = builder.proxy(proxy),
                Err(e) => tracing::warn!("PROXY_URL '{proxy_url}' invalid for OSS client: {e}"),
            }
        }
        let http = builder.build().expect("failed to build OSS reqwest client");

        Some(Self {
            key_id,
            key_secret,
            endpoint,
            bucket,
            base_url,
            http,
        })
    }

    pub fn screenshot_key(page_url: &str) -> String {
        let hash = hex::encode(Sha256::digest(page_url.as_bytes()));
        format!("{OSS_PREFIX}/screenshots/{hash}.png")
    }

    /// `folder` is the sub-directory under the scrapper prefix, e.g. `"og"` or `"logo"`.
    pub fn asset_key(asset_url: &str, folder: &str, content_type: Option<&str>) -> String {
        let hash = hex::encode(Sha256::digest(asset_url.as_bytes()));
        let ext = ext_from_content_type(content_type.unwrap_or(""));
        format!("{OSS_PREFIX}/{folder}/{hash}.{ext}")
    }

    /// Uploads bytes to OSS at `key`. Keys are derived from the source URL (SHA-256),
    /// so the same source URL always maps to the same OSS key. PUT is unconditional —
    /// no deduplication check is performed (no cheap way to check existence without a
    /// HEAD request first, which isn't worth the extra round trip for this workload).
    /// 失败时按指数退避重试最多 3 次；每次尝试的超时由 `self.http` 的构建配置
    /// （`UPLOAD_TIMEOUT_SECS`）保证，无需在这里再包一层。
    /// Returns the full public URL on success.
    pub async fn upload_bytes(
        &self,
        key: &str,
        bytes: &[u8],
        content_type: &str,
    ) -> Result<String, ScrapeError> {
        const MAX_ATTEMPTS: u32 = 3;
        let mut last_err: Option<ScrapeError> = None;

        for attempt in 0..MAX_ATTEMPTS {
            if attempt > 0 {
                let backoff_ms = 200u64 * (1 << (attempt - 1));
                tokio::time::sleep(std::time::Duration::from_millis(backoff_ms)).await;
            }

            match self.upload_bytes_once(key, bytes, content_type).await {
                Ok(url) => return Ok(url),
                Err(e) => {
                    tracing::warn!(
                        "OSS upload attempt {}/{MAX_ATTEMPTS} for key {key} failed: {e:?}",
                        attempt + 1
                    );
                    last_err = Some(e);
                }
            }
        }

        Err(last_err
            .unwrap_or_else(|| ScrapeError::OssFailed("upload retry exhausted".to_string())))
    }

    /// Signs and sends a single `PUT` directly against the OSS virtual-hosted-style
    /// endpoint (`https://{bucket}.{endpoint}/{key}`). Object keys here are always our
    /// own SHA-256 hex digests plus a known extension (see `screenshot_key`/`asset_key`),
    /// so no path-segment escaping is needed for the URL or the canonicalized resource.
    async fn upload_bytes_once(
        &self,
        key: &str,
        bytes: &[u8],
        content_type: &str,
    ) -> Result<String, ScrapeError> {
        let date = httpdate::fmt_http_date(std::time::SystemTime::now());
        // No custom x-oss-* headers and no Content-MD5 header sent, so both are
        // represented as the empty string in the string-to-sign (per the OSS v1 spec).
        let canonicalized_resource = format!("/{}/{}", self.bucket, key);
        let string_to_sign =
            format!("PUT\n\n{content_type}\n{date}\n{canonicalized_resource}");
        let signature = sign_hmac_sha1_base64(&self.key_secret, &string_to_sign);
        let authorization = format!("OSS {}:{}", self.key_id, signature);

        let url = format!("https://{}.{}/{}", self.bucket, self.endpoint, key);

        let response = self
            .http
            .put(&url)
            .header("Date", &date)
            .header("Content-Type", content_type)
            .header("Authorization", authorization)
            .body(bytes.to_vec())
            .send()
            .await
            .map_err(|e| ScrapeError::OssFailed(format!("OSS PUT request failed: {e}")))?;

        let status = response.status();
        if !status.is_success() {
            let body = response.text().await.unwrap_or_default();
            return Err(ScrapeError::OssFailed(format!(
                "OSS PUT failed ({status}) for key {key}: {body}"
            )));
        }

        Ok(format!("{}/{}", self.base_url, key))
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
        assert_eq!(
            OssClient::screenshot_key(url),
            format!("bookmarkify/scrapper/screenshots/{hash}.png")
        );
    }

    #[test]
    fn asset_key_defaults_to_png() {
        let url = "https://example.com/logo";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, "logo", None),
            format!("bookmarkify/scrapper/logo/{hash}.png")
        );
    }

    #[test]
    fn asset_key_uses_jpeg_content_type() {
        let url = "https://example.com/photo";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, "og", Some("image/jpeg")),
            format!("bookmarkify/scrapper/og/{hash}.jpg")
        );
    }

    #[test]
    fn asset_key_uses_webp_content_type() {
        let url = "https://cdn.example.com/img";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            OssClient::asset_key(url, "og", Some("image/webp")),
            format!("bookmarkify/scrapper/og/{hash}.webp")
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

    /// Known-answer test for the HMAC-SHA1 primitive itself, using RFC 2202 test case 1
    /// (key = 20 bytes of 0x0b, data = "Hi There"). This is a conformance check against
    /// an external, independently-published vector — not just "does the wiring compile" —
    /// since a signing bug here would silently break every OSS upload in production.
    #[test]
    fn hmac_sha1_matches_rfc2202_test_case_1() {
        let key = [0x0bu8; 20];
        let key_str = String::from_utf8(key.to_vec()).unwrap_or_default();
        // The RFC 2202 key is raw bytes, not necessarily valid UTF-8 text, but 0x0b
        // *is* valid single-byte UTF-8, so this round-trips exactly for this vector.
        assert_eq!(key_str.as_bytes(), &key[..]);

        let mut mac = Hmac::<Sha1>::new_from_slice(&key).unwrap();
        mac.update(b"Hi There");
        let raw = mac.finalize().into_bytes();
        assert_eq!(
            hex::encode(raw),
            "b617318655057264e28bc0b6fb378c8ef146be00",
            "HMAC-SHA1 does not match RFC 2202 test case 1"
        );

        // Now confirm our helper just base64-encodes that same value.
        let expected_b64 = {
            use base64::{engine::general_purpose::STANDARD, Engine};
            STANDARD.encode(raw)
        };
        assert_eq!(sign_hmac_sha1_base64(&key_str, "Hi There"), expected_b64);
    }

    #[test]
    fn sign_hmac_sha1_base64_is_deterministic_and_key_dependent() {
        let a = sign_hmac_sha1_base64("secret-a", "PUT\n\nimage/png\nsome-date\n/bucket/key");
        let b = sign_hmac_sha1_base64("secret-a", "PUT\n\nimage/png\nsome-date\n/bucket/key");
        let c = sign_hmac_sha1_base64("secret-b", "PUT\n\nimage/png\nsome-date\n/bucket/key");
        assert_eq!(a, b, "same inputs must produce the same signature");
        assert_ne!(a, c, "different secrets must produce different signatures");
    }
}
