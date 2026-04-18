use once_cell::sync::Lazy;
use spider::configuration::{ScreenShotConfig, ScreenshotParams};
use spider::features::chrome_common::{RequestInterceptConfiguration, WaitForDelay};
use spider::website::Website;
use std::time::Duration;
use tokio::sync::Mutex;

use crate::scraper::{parse_metadata, ScrapeError, ScrapeResult};

// Serialize all headless requests — one Chrome operation at a time
static HEADLESS_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

pub async fn scrape_headless(url: &str, timeout_secs: u64) -> Result<ScrapeResult, ScrapeError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;

    let _guard = HEADLESS_LOCK.lock().await;

    let screenshot_config = ScreenShotConfig::new(
        ScreenshotParams::new(Default::default(), Some(true), None),
        true,  // return bytes in page.screenshot_bytes
        false, // do not save to disk
        None,
    );

    let mut website = Website::new(url);
    website
        .with_stealth(true)
        .with_chrome_intercept(RequestInterceptConfiguration::new(true))
        .with_wait_for_delay(Some(WaitForDelay {
            timeout: Some(Duration::from_secs(5)),
        }))
        .with_screenshot(Some(screenshot_config));

    let pages = tokio::time::timeout(Duration::from_secs(timeout_secs), async move {
        website.scrape().await;
        website.get_pages().map(|p| p.to_vec())
    })
    .await
    .map_err(|_| ScrapeError::Timeout)?;

    let page = match pages {
        Some(p) if !p.is_empty() => p[0].clone(),
        _ => return Err(ScrapeError::HeadlessFailed("no page returned".to_string())),
    };

    let html = page.get_html().to_string();
    if html.is_empty() {
        return Err(ScrapeError::HeadlessFailed("empty page html".to_string()));
    }

    let screenshot_bytes = page.screenshot_bytes.clone();

    let mut result = parse_metadata(&html, &parsed);
    result.source = "headless".to_string();
    result.screenshot_bytes = screenshot_bytes;

    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn headless_huaban_returns_metadata() {
        let result = scrape_headless("https://huaban.com/", 60).await;
        assert!(result.is_ok(), "scrape_headless failed: {:?}", result.err());
        let r = result.unwrap();
        assert!(r.title.is_some(), "title should not be None");
        assert!(!r.title.as_deref().unwrap_or("").is_empty(), "title should not be empty");
        assert_eq!(r.source, "headless");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn headless_huaban_returns_screenshot_bytes() {
        let result = scrape_headless("https://huaban.com/", 60).await;
        assert!(result.is_ok(), "scrape_headless failed: {:?}", result.err());
        let r = result.unwrap();
        assert!(r.screenshot_bytes.is_some(), "screenshot_bytes should not be None");
        let bytes = r.screenshot_bytes.unwrap();
        assert!(bytes.len() > 10_240, "screenshot should be > 10KB, got {} bytes", bytes.len());
    }
}
