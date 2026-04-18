use crate::scraper::{ScrapeError, ScrapeResult};

pub async fn scrape_headless(url: &str, timeout_secs: u64) -> Result<ScrapeResult, ScrapeError> {
    let _ = (url, timeout_secs);
    Err(ScrapeError::HeadlessFailed("not implemented".to_string()))
}
