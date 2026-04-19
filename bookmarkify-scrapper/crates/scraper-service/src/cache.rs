use std::sync::Arc;
use std::time::Duration;
use moka::future::Cache;
use crate::scraper::ScrapeResult;

/// 基于 URL 的抓取结果内存缓存，支持自动过期和容量限制。
///
/// 使用 [`moka`] 异步缓存实现，最多存储 10,000 条记录。
/// 缓存键在存入和查询时均会经过 URL 规范化（排序查询参数、去除 fragment），
/// 保证同一资源的不同 URL 写法能命中同一条缓存。
pub struct ScrapeCache {
    /// 底层 moka 异步缓存，键为规范化后的 URL 字符串，值为共享的抓取结果
    inner: Cache<String, Arc<ScrapeResult>>,
}

impl ScrapeCache {
    /// 创建一个新的 `ScrapeCache` 实例。
    ///
    /// # 参数
    /// - `ttl_secs`：缓存条目的存活时间（秒）。超过该时间后条目自动失效。
    ///
    /// # 容量
    /// 最多缓存 10,000 条 URL 记录；超出时 moka 会按 LRU 策略淘汰旧条目。
    pub fn new(ttl_secs: u64) -> Self {
        Self {
            inner: Cache::builder()
                .max_capacity(10_000)
                .time_to_live(Duration::from_secs(ttl_secs))
                .build(),
        }
    }

    /// 查询指定 URL 的缓存结果。
    ///
    /// URL 会先经过规范化处理（见 [`Self::normalize`]）再查找。
    /// 若 URL 非法（无法解析）则直接返回 `None`。
    ///
    /// # 参数
    /// - `url`：目标 URL 字符串（原始形式，无需预先规范化）
    ///
    /// # 返回
    /// 命中时返回 `Some(Arc<ScrapeResult>)`，未命中或 URL 非法时返回 `None`。
    pub async fn get(&self, url: &str) -> Option<Arc<ScrapeResult>> {
        let key = Self::normalize(url)?;
        self.inner.get(&key).await
    }

    /// 将抓取结果存入缓存，以规范化后的 URL 作为键。
    ///
    /// 若 URL 非法（无法规范化），则静默忽略，不写入缓存。
    ///
    /// # 参数
    /// - `url`：目标 URL 字符串
    /// - `result`：要缓存的抓取结果（通过 `Arc` 共享所有权）
    pub async fn set(&self, url: &str, result: Arc<ScrapeResult>) {
        if let Some(key) = Self::normalize(url) {
            self.inner.insert(key, result).await;
        }
    }

    /// 将 URL 规范化为统一的缓存键格式。
    ///
    /// 执行以下规范化步骤：
    /// 1. 解析 URL（若非法则返回 `None`）
    /// 2. 将查询参数按键名字母序排序（消除参数顺序差异）
    /// 3. 移除 URL fragment（`#` 之后的部分，不影响服务端响应）
    ///
    /// 注意：`reqwest::Url::parse` 会自动将 host 转为小写，
    /// 因此 `https://EXAMPLE.COM` 和 `https://example.com` 会映射到同一键。
    ///
    /// # 参数
    /// - `url`：原始 URL 字符串
    ///
    /// # 返回
    /// 规范化后的 URL 字符串，或 `None`（URL 非法时）。
    fn normalize(url: &str) -> Option<String> {
        let mut parsed = reqwest::Url::parse(url).ok()?;
        // 将查询参数按键名字母序排序，消除顺序差异
        let mut pairs: Vec<(String, String)> = parsed
            .query_pairs()
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        if pairs.is_empty() {
            parsed.set_query(None);
        } else {
            pairs.sort_by(|a, b| a.0.cmp(&b.0));
            {
                let mut qm = parsed.query_pairs_mut();
                qm.clear();
                for (k, v) in &pairs {
                    qm.append_pair(k, v);
                }
            }
        }
        // 移除 fragment，服务端不区分 fragment 差异
        parsed.set_fragment(None);
        Some(parsed.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_result(title: &str) -> Arc<ScrapeResult> {
        Arc::new(ScrapeResult {
            title: Some(title.to_string()),
            description: None,
            image: None,
            favicon: None,
            source: "og".to_string(),
            screenshot_bytes: None,
            screenshot_url: None,
        })
    }

    #[test]
    fn normalize_removes_fragment() {
        let result = ScrapeCache::normalize("https://example.com/page#section");
        assert_eq!(result, Some("https://example.com/page".to_string()));
    }

    #[test]
    fn normalize_sorts_query_params() {
        let result = ScrapeCache::normalize("https://example.com/?b=2&a=1");
        assert_eq!(result, Some("https://example.com/?a=1&b=2".to_string()));
    }

    #[test]
    fn normalize_lowercases_host() {
        let result = ScrapeCache::normalize("https://EXAMPLE.COM/path");
        assert_eq!(result, Some("https://example.com/path".to_string()));
    }

    #[test]
    fn normalize_invalid_url_returns_none() {
        let result = ScrapeCache::normalize("not-a-url");
        assert_eq!(result, None);
    }

    #[tokio::test]
    async fn cache_hit_returns_same_result() {
        let cache = ScrapeCache::new(3600);
        let url = "https://example.com/";
        let r = make_result("Hello");
        cache.set(url, Arc::clone(&r)).await;
        let got = cache.get(url).await;
        assert!(got.is_some());
        assert_eq!(got.unwrap().title, r.title);
    }

    #[tokio::test]
    async fn cache_miss_returns_none() {
        let cache = ScrapeCache::new(3600);
        let got = cache.get("https://example.com/never-set").await;
        assert!(got.is_none());
    }

    #[tokio::test]
    async fn cache_normalizes_key_on_set_and_get() {
        let cache = ScrapeCache::new(3600);
        let r = make_result("Frag Test");
        cache.set("https://example.com/page#hash", Arc::clone(&r)).await;
        // same URL without fragment should hit
        let got = cache.get("https://example.com/page").await;
        assert!(got.is_some(), "should hit cache ignoring fragment");
    }

    #[tokio::test]
    async fn cache_normalizes_query_param_order_on_set_and_get() {
        let cache = ScrapeCache::new(3600);
        let r = make_result("Sorted");
        cache.set("https://example.com/?z=3&a=1", Arc::clone(&r)).await;
        let got = cache.get("https://example.com/?a=1&z=3").await;
        assert!(got.is_some(), "should hit cache regardless of query param order");
    }
}
