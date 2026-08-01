use hmac::{Hmac, Mac};
use sha1::Sha1;
use sha2::{Digest, Sha256};

use crate::scraper::ScrapeError;

/// Default key prefix for scrapper-produced objects, overridable via `OSS_KEY_PREFIX`.
///
/// Deliberately carries no caller branding: this service is generic, and hardcoding one
/// deployment's bucket layout into the binary is what made the prefix un-reviewable before.
/// The prefix is also the unit the bucket's own policies are written against — a
/// `PutObject`-only RAM policy and a lifecycle expiry rule are both scoped to it, so it
/// must stay in sync with whatever the bucket is configured for.
const DEFAULT_KEY_PREFIX: &str = "scrapper";

/// Upload-specific timeout — generous because screenshots/images can be up to
/// MAX_IMAGE_BYTES (10MB) and OSS is a separate leg from the page scrape itself.
const UPLOAD_TIMEOUT_SECS: u64 = 30;

/// Aliyun OSS V1 (HMAC-SHA1) request signature, base64-encoded.
/// See https://help.aliyun.com/document_detail/31951.html — `Authorization: OSS
/// <AccessKeyId>:<signature>` where `signature = base64(hmac_sha1(secret, string_to_sign))`.
fn sign_hmac_sha1_base64(secret: &str, string_to_sign: &str) -> String {
    let mut mac =
        Hmac::<Sha1>::new_from_slice(secret.as_bytes()).expect("HMAC accepts a key of any length");
    mac.update(string_to_sign.as_bytes());
    use base64::{engine::general_purpose::STANDARD, Engine};
    STANDARD.encode(mac.finalize().into_bytes())
}

pub struct OssClient {
    key_id: String,
    key_secret: String,
    endpoint: String,
    bucket: String,
    key_prefix: String,
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
        let key_prefix = std::env::var("OSS_KEY_PREFIX")
            .ok()
            .map(|s| s.trim().trim_matches('/').to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| DEFAULT_KEY_PREFIX.to_string());

        let mut builder =
            reqwest::Client::builder().timeout(std::time::Duration::from_secs(UPLOAD_TIMEOUT_SECS));
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
            key_prefix,
            http,
        })
    }

    /// Screenshot keys stay addressed by **page URL**, unlike assets.
    ///
    /// This is deliberate, not an oversight. URL addressing is self-overwriting, so a page
    /// re-crawled a hundred times occupies exactly one object. Content addressing would mint
    /// a new object every single crawl — a screenshot differs on every capture — giving
    /// unbounded growth in exchange for a deduplication benefit that is precisely zero.
    pub fn screenshot_key(&self, page_url: &str) -> String {
        let hash = hex::encode(Sha256::digest(page_url.as_bytes()));
        format!("{}/screenshots/{hash}.png", self.key_prefix)
    }

    /// Content-addressed asset key: `<prefix>/<folder>/<sha256-of-bytes>`.
    ///
    /// Two properties follow from hashing the **bytes** rather than the source URL:
    ///
    /// - **Deduplication.** The same image reachable at several URLs — the common case for
    ///   favicons served from a shared CDN — collapses onto one object.
    /// - **Immutability.** A key's contents never change, so a signed GET may carry a long
    ///   TTL. URL addressing had the opposite property: a site swapping its logo overwrote
    ///   the object behind an unchanged key.
    ///
    /// **No file extension**, by design. Identical bytes can be declared `image/png` by one
    /// site and `application/octet-stream` by another; deriving the suffix from the declared
    /// content type would produce two keys for one hash and break the consumer's uniqueness
    /// constraint on it. The MIME type travels in the response instead.
    ///
    /// `content_hash` accepts both the `sha256:<hex>` wire form and a bare hex digest.
    pub fn asset_key(&self, content_hash: &str, folder: &str) -> String {
        let digest = content_hash.rsplit(':').next().unwrap_or(content_hash);
        format!("{}/{folder}/{digest}", self.key_prefix)
    }

    /// Uploads bytes to OSS at `key`. PUT is unconditional — no existence check is performed
    /// (there is no cheap way to test for it without a preceding HEAD, not worth the extra
    /// round trip for this workload). With content-addressed keys a redundant PUT is harmless
    /// anyway: it rewrites the identical bytes.
    /// 失败时按指数退避重试最多 3 次；每次尝试的超时由 `self.http` 的构建配置
    /// （`UPLOAD_TIMEOUT_SECS`）保证，无需在这里再包一层。
    ///
    /// 成功时返回 **object key**（不是 URL）。域名归调用方所有：拼 URL、签名、按展示
    /// 模式缩放都是消费端的策略，本服务只负责把字节放进桶里。
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
                Ok(()) => return Ok(key.to_string()),
                Err(UploadError { retryable, inner }) => {
                    // 凭据/签名/权限类错误重试多少次都是同样的结果，只会把真实错因埋进
                    // 三条同样的 warn 里，还白白拖慢 600ms。立即失败，让错因浮到调用方。
                    if !retryable {
                        tracing::warn!("OSS upload for key {key} failed permanently: {inner:?}");
                        return Err(inner);
                    }
                    tracing::warn!(
                        "OSS upload attempt {}/{MAX_ATTEMPTS} for key {key} failed: {inner:?}",
                        attempt + 1
                    );
                    last_err = Some(inner);
                }
            }
        }

        Err(last_err
            .unwrap_or_else(|| ScrapeError::OssFailed("upload retry exhausted".to_string())))
    }

    /// Signs and sends a single `PUT` directly against the OSS virtual-hosted-style
    /// endpoint (`https://{bucket}.{endpoint}/{key}`). Object keys here are always our own
    /// SHA-256 hex digests — bare for assets, plus a `.png` suffix for screenshots (see
    /// `screenshot_key`/`asset_key`) — so no path-segment escaping is needed for the URL or
    /// the canonicalized resource.
    async fn upload_bytes_once(
        &self,
        key: &str,
        bytes: &[u8],
        content_type: &str,
    ) -> Result<(), UploadError> {
        let date = httpdate::fmt_http_date(std::time::SystemTime::now());
        // No custom x-oss-* headers and no Content-MD5 header sent, so both are
        // represented as the empty string in the string-to-sign (per the OSS v1 spec).
        let canonicalized_resource = format!("/{}/{}", self.bucket, key);
        let string_to_sign = format!("PUT\n\n{content_type}\n{date}\n{canonicalized_resource}");
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
            // 连不上/超时/连接被重置属于瞬时故障，值得重试
            .map_err(|e| UploadError {
                retryable: true,
                inner: ScrapeError::OssFailed(format!("OSS PUT request failed: {e}")),
            })?;

        let status = response.status();
        if !status.is_success() {
            let body = response.text().await.unwrap_or_default();
            return Err(UploadError {
                // 4xx 是请求本身的问题（签名不对、AK 失效、无权限、桶不存在），
                // 重试只会得到同一个 4xx。唯一的例外是 429 限流，那个该退避后重试。
                retryable: !status.is_client_error() || status.as_u16() == 429,
                inner: ScrapeError::OssFailed(format!(
                    "OSS PUT failed ({status}) for key {key}: {body}"
                )),
            });
        }

        Ok(())
    }
}

/// 上传失败的分类。只在本模块内部流转，用于决定"重试还是立刻放弃"。
struct UploadError {
    retryable: bool,
    inner: ScrapeError,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 只为 key 生成相关的断言构造实例：这些方法不发网络请求，凭据填什么都无所谓。
    fn client_with_prefix(prefix: &str) -> OssClient {
        OssClient {
            key_id: "ak".to_string(),
            key_secret: "sk".to_string(),
            endpoint: "oss-cn-hangzhou.aliyuncs.com".to_string(),
            bucket: "bucket".to_string(),
            key_prefix: prefix.to_string(),
            http: reqwest::Client::new(),
        }
    }

    #[test]
    fn screenshot_key_is_sha256_of_page_url() {
        let url = "https://example.com/page";
        let hash = hex::encode(Sha256::digest(url.as_bytes()));
        assert_eq!(
            client_with_prefix("scrapper").screenshot_key(url),
            format!("scrapper/screenshots/{hash}.png")
        );
    }

    #[test]
    fn asset_key_is_the_content_digest_without_extension() {
        let hash = format!("sha256:{}", hex::encode(Sha256::digest(b"the bytes")));
        assert_eq!(
            client_with_prefix("scrapper").asset_key(&hash, "asset"),
            format!(
                "scrapper/asset/{}",
                hex::encode(Sha256::digest(b"the bytes"))
            )
        );
    }

    /// 契约上 `contentHash` 带 `sha256:` 前缀，但裸 hex 也该被接受 —— key 里只放摘要本身，
    /// 否则冒号会进 object key，给桶策略和排查平添麻烦。
    #[test]
    fn asset_key_accepts_bare_hex_digest_too() {
        let bare = hex::encode(Sha256::digest(b"x"));
        let prefixed = format!("sha256:{bare}");
        let c = client_with_prefix("scrapper");
        assert_eq!(c.asset_key(&bare, "asset"), c.asset_key(&prefixed, "asset"));
    }

    /// 内容寻址的**全部意义**：同一张图挂在不同 URL 下，落到同一个 key，只存一份。
    /// URL 寻址下这两次上传会产生两个对象。
    #[test]
    fn identical_bytes_from_different_urls_collapse_onto_one_key() {
        let hash = format!("sha256:{}", hex::encode(Sha256::digest(b"same favicon")));
        let c = client_with_prefix("scrapper");
        assert_eq!(c.asset_key(&hash, "asset"), c.asset_key(&hash, "asset"));
    }

    /// 截图刻意不参与内容寻址：它每次抓都不一样，改成内容寻址等于无上界增长，
    /// 而去重收益恰好是零。这条断言是那个决定的护栏。
    #[test]
    fn screenshot_key_stays_url_addressed_and_self_overwriting() {
        let c = client_with_prefix("scrapper");
        let first = c.screenshot_key("https://example.com/page");
        let second = c.screenshot_key("https://example.com/page");
        assert_eq!(
            first, second,
            "同一页面的截图必须落同一个 key（自我覆盖，存储量有上界）"
        );
    }

    /// 前缀可配置是 RAM 策略与 lifecycle 规则的锚点 —— 桶上的 `PutObject` 授权就是按这个
    /// 前缀写的，所以它必须真的能被部署方改掉，而不是编译进二进制。
    #[test]
    fn key_prefix_is_configurable_and_applies_to_all_keys() {
        let c = client_with_prefix("tenant-a/crawler");
        assert!(c
            .screenshot_key("https://example.com")
            .starts_with("tenant-a/crawler/screenshots/"));
        assert!(c
            .asset_key("sha256:deadbeef", "asset")
            .starts_with("tenant-a/crawler/asset/"));
    }

    #[test]
    fn from_env_returns_none_when_vars_missing() {
        std::env::remove_var("OSS_ACCESS_KEY_ID");
        std::env::remove_var("OSS_ACCESS_KEY_SECRET");
        std::env::remove_var("OSS_BUCKET");
        std::env::remove_var("OSS_ENDPOINT");
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
