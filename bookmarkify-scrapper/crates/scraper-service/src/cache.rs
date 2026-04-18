use std::sync::Arc;
use std::time::Duration;
use moka::future::Cache;
use crate::scraper::ScrapeResult;

pub struct ScrapeCache {
    inner: Cache<String, Arc<ScrapeResult>>,
}

impl ScrapeCache {
    pub fn new(ttl_secs: u64) -> Self {
        Self {
            inner: Cache::builder()
                .time_to_live(Duration::from_secs(ttl_secs))
                .build(),
        }
    }

    pub async fn get(&self, url: &str) -> Option<Arc<ScrapeResult>> {
        let key = Self::normalize(url)?;
        self.inner.get(&key).await
    }

    pub async fn set(&self, url: &str, result: Arc<ScrapeResult>) {
        if let Some(key) = Self::normalize(url) {
            self.inner.insert(key, result).await;
        }
    }

    fn normalize(url: &str) -> Option<String> {
        let mut parsed = reqwest::Url::parse(url).ok()?;
        // sort query params alphabetically
        let mut pairs: Vec<(String, String)> = parsed
            .query_pairs()
            .map(|(k, v)| (k.into_owned(), v.into_owned()))
            .collect();
        if pairs.is_empty() {
            parsed.set_query(None);
        } else {
            pairs.sort_by(|a, b| a.0.cmp(&b.0));
            let query = pairs
                .iter()
                .map(|(k, v)| format!("{}={}", k, v))
                .collect::<Vec<_>>()
                .join("&");
            parsed.set_query(Some(&query));
        }
        // remove fragment
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
}
