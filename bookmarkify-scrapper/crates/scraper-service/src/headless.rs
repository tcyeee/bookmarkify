use once_cell::sync::Lazy;
use spider::configuration::{ScreenShotConfig, ScreenshotParams};
use spider::features::chrome_common::{RequestInterceptConfiguration, WaitForDelay};
use spider::website::Website;
use std::time::Duration;
use tokio::sync::Mutex;

use crate::scraper::{parse_metadata, ScrapeError, ScrapeResult};

/// 全局互斥锁，保证同一时刻只有一个无头 Chrome 操作在运行。
///
/// Chrome 实例的内存和 CPU 开销较大，并发启动多个实例容易导致资源耗尽或崩溃。
/// 通过此锁将所有无头请求串行化，以换取更高的稳定性。
static HEADLESS_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

/// 通过无头 Chrome（Layer 2）抓取指定 URL 的页面元数据和截图。
///
/// 适用于重度依赖 JavaScript 渲染、普通 HTTP 请求无法获取有效内容的页面。
/// 与普通抓取（[`crate::scraper::scrape`]）不同，此函数会：
/// - 启动真实的 Chrome 浏览器并等待页面渲染完成
/// - 启用隐身模式（`with_stealth`）和请求拦截（屏蔽广告/追踪请求）以降低被反爬检测的概率
/// - 等待最多 5 秒让页面动态内容加载完毕
/// - 捕获全页截图（PNG 字节）存入结果的 `screenshot_bytes` 字段
///
/// 所有无头请求通过 [`HEADLESS_LOCK`] 串行执行，避免并发 Chrome 实例导致资源耗尽。
///
/// # 参数
/// - `url`：目标页面 URL 字符串
/// - `timeout_secs`：整个无头抓取流程的最大等待时间（秒），超时后返回 `ScrapeError::Timeout`
///
/// # 返回
/// 成功时返回 `Ok(ScrapeResult)`，`source` 字段固定为 `"headless"`，
/// 并附带 `screenshot_bytes`；失败时返回对应的 `ScrapeError`：
/// - `InvalidUrl`：URL 格式非法
/// - `Timeout`：超过 `timeout_secs` 仍未完成
/// - `HeadlessFailed`：Chrome 未返回页面或页面 HTML 为空
pub async fn scrape_headless(url: &str, timeout_secs: u64) -> Result<ScrapeResult, ScrapeError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;

    // 获取全局锁，确保同一时刻只运行一个 Chrome 实例
    let _guard = HEADLESS_LOCK.lock().await;

    // 配置截图：在内存中返回字节，不写入磁盘
    let screenshot_config = ScreenShotConfig::new(
        ScreenshotParams::new(Default::default(), Some(true), None),
        true,  // return bytes in page.screenshot_bytes
        false, // do not save to disk
        None,
    );

    let mut website = Website::new(url);
    website
        .with_stealth(true) // 启用隐身模式，降低被检测为爬虫的概率
        .with_chrome_intercept(RequestInterceptConfiguration::new(true)) // 拦截广告/追踪请求，加快加载速度
        .with_wait_for_delay(Some(WaitForDelay {
            timeout: Some(Duration::from_secs(5)), // 等待 5 秒让 JS 渲染完成
        }))
        .with_screenshot(Some(screenshot_config));

    // 在总超时限制内执行抓取，超时则返回 Timeout 错误
    let pages = tokio::time::timeout(Duration::from_secs(timeout_secs), async move {
        website.scrape().await;
        website.get_pages().map(|p| p.to_vec())
    })
    .await
    .map_err(|_| ScrapeError::Timeout)?;

    // 取第一个页面；未返回任何页面视为失败
    let page = match pages {
        Some(p) if !p.is_empty() => p[0].clone(),
        _ => return Err(ScrapeError::HeadlessFailed("no page returned".to_string())),
    };

    let html = page.get_html().to_string();
    if html.is_empty() {
        return Err(ScrapeError::HeadlessFailed("empty page html".to_string()));
    }

    let screenshot_bytes = page.screenshot_bytes.clone();

    // 复用普通抓取的元数据解析逻辑，然后覆盖 source 和截图字节
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
