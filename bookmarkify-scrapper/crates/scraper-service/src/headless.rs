use once_cell::sync::Lazy;
use spider::configuration::{ScreenShotConfig, ScreenshotParams};
use spider::features::chrome_common::{RequestInterceptConfiguration, WaitForIdleNetwork};
use spider::website::Website;
use std::time::Duration;
use tokio::sync::Mutex;

use crate::scraper::{parse_metadata, validate_target_host, validate_url_scheme, ScrapeError, ScrapeResult};

/// 全局互斥锁，保证同一时刻只有一个无头 Chrome 操作在运行。
///
/// Chrome 实例的内存和 CPU 开销较大，并发启动多个实例容易导致资源耗尽或崩溃。
/// 通过此锁将所有无头请求串行化，以换取更高的稳定性。
static HEADLESS_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

/// 通过无头 Chrome（Layer 2）抓取指定 URL 的页面元数据和截图。
///
/// 适用于重度依赖 JavaScript 渲染、普通 HTTP 请求无法获取有效内容的页面。
///
/// # 参数
/// - `url`：目标页面 URL 字符串
/// - `timeout_secs`：整个无头抓取流程的最大等待时间（秒），对应 `HEADLESS_TIMEOUT_SECS`
/// - `idle_wait_secs`：网络空闲等待时间（秒），用于等待 JS 渲染完成，对应 `HEADLESS_IDLE_WAIT_SECS`
///
/// # 返回
/// 成功时返回 `Ok(ScrapeResult)`，`source` 字段固定为 `"headless"`，
/// 并附带 `screenshot_bytes`；失败时返回对应的 `ScrapeError`：
/// - `InvalidUrl`：URL 格式非法
/// - `Timeout`：超过 `timeout_secs` 仍未完成
/// - `HeadlessFailed`：Chrome 未返回页面或页面 HTML 为空
pub async fn scrape_headless(url: &str, timeout_secs: u64, idle_wait_secs: u64) -> Result<ScrapeResult, ScrapeError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;
    validate_url_scheme(&parsed)?;
    validate_target_host(&parsed).await?;

    // 获取全局锁，确保同一时刻只运行一个 Chrome 实例。
    // 锁等待时间不超过总超时预算，避免请求队列无限堆积。
    let _guard = tokio::time::timeout(
        Duration::from_secs(timeout_secs),
        HEADLESS_LOCK.lock(),
    )
    .await
    .map_err(|_| ScrapeError::Timeout)?;

    // Chrome 崩溃后会遗留 SingletonLock 文件，阻止下次启动；持锁后安全清除。
    // 同时处理 macOS 上 /var → /private/var 的 symlink，用 remove_dir_all 彻底清除 profile 目录。
    let runner_dir = std::env::temp_dir().join("chromiumoxide-runner");
    let singleton_lock = runner_dir.join("SingletonLock");
    if let Err(e) = std::fs::remove_file(&singleton_lock) {
        if e.kind() != std::io::ErrorKind::NotFound {
            // 删除单个文件失败时（权限或已变为目录），直接清除整个 runner 目录
            let _ = std::fs::remove_dir_all(&runner_dir);
        }
    }

    // 配置截图：在内存中返回字节，不写入磁盘
    let screenshot_config = ScreenShotConfig::new(
        ScreenshotParams::new(Default::default(), Some(true), None),
        true,  // return bytes in page.screenshot_bytes
        false, // do not save to disk
        None,
    );

    let mut website = Website::new(url);
    website
        .with_limit(1) // 只抓取目标页面，不跟随任何链接
        .with_stealth(true) // 启用隐身模式，降低被检测为爬虫的概率
        .with_chrome_intercept(RequestInterceptConfiguration::new(true)) // 拦截广告/追踪请求（依赖 spider 的 chrome_intercept feature）
        .with_wait_for_idle_network(Some(WaitForIdleNetwork::new(Some(Duration::from_secs(idle_wait_secs))))) // 等待网络空闲（JS 渲染完成）
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
        let result = scrape_headless("https://huaban.com/", 60, 10).await;
        assert!(result.is_ok(), "scrape_headless failed: {:?}", result.err());
        let r = result.unwrap();
        assert!(r.title.is_some(), "title should not be None");
        assert!(!r.title.as_deref().unwrap_or("").is_empty(), "title should not be empty");
        assert_eq!(r.source, "headless");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn headless_huaban_returns_screenshot_bytes() {
        let result = scrape_headless("https://huaban.com/", 60, 10).await;
        assert!(result.is_ok(), "scrape_headless failed: {:?}", result.err());
        let r = result.unwrap();
        assert!(r.screenshot_bytes.is_some(), "screenshot_bytes should not be None");
        let bytes = r.screenshot_bytes.unwrap();
        assert!(bytes.len() > 10_240, "screenshot should be > 10KB, got {} bytes", bytes.len());
    }
}
