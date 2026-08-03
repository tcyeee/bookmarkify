use once_cell::sync::Lazy;
use spider::configuration::{
    CaptureScreenshotFormat, CaptureScreenshotParams, ScreenShotConfig, ScreenshotParams,
};
use spider::features::chrome_common::{RequestInterceptConfiguration, WaitForIdleNetwork};
use spider::website::Website;
use std::time::Duration;
use tokio::sync::Mutex;

use crate::contract::{ImageFormat, ScreenshotOptions, Viewport};
use crate::scraper::{validate_target_host, validate_url_scheme, ScrapeError};

/// 无头抓取的产物。元数据解析统一交给 [`crate::extract`]，与 Layer 1 走同一条路径 ——
/// 两层的差别只应该是"HTML 怎么拿到的"，不该各自解析一遍。
#[derive(Debug)]
pub struct HeadlessCapture {
    pub html: String,
    /// PNG 截图字节；Chrome 未返回截图时为 None
    pub screenshot_bytes: Option<Vec<u8>>,
    /// 本次导航的 HTTP 状态码。
    ///
    /// **拿到 HTML 不等于抓成功**：风控拦截页同样是一篇有 `<title>` 的正常文档，
    /// 只是状态码是 403/412。没有这个字段，调用方只能把"出错啦! - bilibili.com"
    /// 当作站点标题存下来。spider 还会把 Chrome 自己的网络错误页（`ERR_*`，它们
    /// 以 200 返回）归一化成 599，所以非 2xx 一律代表这篇 HTML 不可信。
    pub status: u16,
}

/// 资源拦截配置：截图时不拦 CSS / 图片 / 字体，其余情况按默认拦。
///
/// `RequestInterceptConfiguration::new(true)` 并不只是"拦广告"：它的构造函数同时把
/// `block_visuals`（Image/Media/Font）和 `block_stylesheets`（CSS）置真。对 Layer 2 的
/// **主要用途**——拿到 JS 渲染后的 HTML 去解析元数据——这完全正确，省下的内存在 1GB
/// 容器里是刚需；而截图要的是"这个页面长什么样"，拦掉样式与图片在语义上就是错的。
///
/// **注意：放开它并不能让截图变好看。** 实测（stripe.com，1280×720）无论这两个开关取
/// 什么值、乃至把 `enabled` 整个关掉，截出来的都是同一张逐字节相同的裸 HTML —— 所以
/// 「截图没有样式」另有原因，仍未定位，见 `screenshot_still_renders_unstyled` 的说明。
/// 这里保留正确的语义，但它不是那个 bug 的解药。
fn intercept_config(want_screenshot: bool) -> RequestInterceptConfiguration {
    let mut c = RequestInterceptConfiguration::new(true);
    if want_screenshot {
        c.block_visuals = false;
        c.block_stylesheets = false;
        // block_analytics 保持开启：埋点与观感无关，拦掉只有好处
    }
    c
}

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
/// - `viewport`：视口宽高与像素比；`None` 时用 Chrome 默认视口
/// - `screenshot`：`None` 时跳过截图，省下一次全页渲染与编码；`Some` 时同时放开
///   资源拦截（见函数体内 `intercept` 的注释）
///
/// # 返回
/// 成功时返回 `Ok(HeadlessCapture)`（渲染后的 HTML + 导航状态码 + 可选截图）。注意
/// **非 2xx 不算失败**：拦截页也是页面，是否采信交给调用方按场景判断（见
/// [`HeadlessCapture::status`]）。失败时返回对应的 `ScrapeError`：
/// - `InvalidUrl`：URL 格式非法
/// - `Timeout`：超过 `timeout_secs` 仍未完成
/// - `HeadlessFailed`：Chrome 未返回页面或页面 HTML 为空
pub async fn capture_headless(
    url: &str,
    timeout_secs: u64,
    idle_wait_secs: u64,
    viewport: Option<Viewport>,
    screenshot: Option<ScreenshotOptions>,
) -> Result<HeadlessCapture, ScrapeError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;
    validate_url_scheme(&parsed)?;
    validate_target_host(&parsed).await?;

    // 以共享 deadline 约束锁等待 + Chrome 执行，确保两阶段合计不超过 timeout_secs。
    // 旧代码对两个阶段各用独立的 timeout_secs，最坏情况总延迟达 2×timeout_secs。
    let deadline = tokio::time::Instant::now() + Duration::from_secs(timeout_secs);

    let _guard = tokio::time::timeout_at(deadline, HEADLESS_LOCK.lock())
        .await
        .map_err(|_| {
            ScrapeError::Timeout(format!(
                "等待无头浏览器全局锁超过 {timeout_secs}s（另一个抓取仍占用 Chrome）"
            ))
        })?;

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

    // 配置截图：在内存中返回字节，不写入磁盘。不要截图时整个配置传 None，
    // 让 Chrome 省掉一次全页渲染与编码。
    let screenshot_config = screenshot.as_ref().map(|opts| {
        let cdp_params = CaptureScreenshotParams {
            format: Some(match opts.format {
                ImageFormat::Png => CaptureScreenshotFormat::Png,
                ImageFormat::Jpeg => CaptureScreenshotFormat::Jpeg,
                ImageFormat::Webp => CaptureScreenshotFormat::Webp,
            }),
            // PNG 是无损的，给它带 quality 只会让 CDP 参数自相矛盾；只有损格式才设
            quality: match opts.format {
                ImageFormat::Png => None,
                _ => Some(opts.quality as i64),
            },
            ..Default::default()
        };
        ScreenShotConfig::new(
            // omit_background 必须显式传 Some(false)：spider 在它为 None 时会去读
            // SCREENSHOT_OMIT_BACKGROUND 环境变量，而变量缺省时兜底值是 **true**
            // （见 spider chrome_common.rs 的 From<ScreenshotParams>）。那意味着页面
            // 背景被抠成透明 —— 对"这个页面长什么样"这件事是纯粹的破坏。
            ScreenshotParams::new(cdp_params, Some(opts.full_page), Some(false)),
            true,  // return bytes in page.screenshot_bytes
            false, // do not save to disk
            None,
        )
    });

    let intercept = intercept_config(screenshot.is_some());

    // 复用 PROXY_URL 环境变量：非空时让无头 Chrome 也走代理。
    // spider 据此为 Chrome 拼出 --proxy-server 启动参数（见 spider features/chrome.rs），
    // 与 main.rs 中 reqwest 客户端的代理保持一致。None 时为无操作（直连）。
    let proxies = std::env::var("PROXY_URL")
        .ok()
        .filter(|s| !s.is_empty())
        .map(|url| vec![url]);

    let mut website = Website::new(url);
    website
        // 只抓取目标页面，不跟随任何链接。注意 spider 的预算判定存在 off-by-one：
        // is_over_inner_budget 在 budget==1 时即判定"超预算"并跳过该链接，因此 with_limit(1)
        // 会连种子页本身都跳过（返回零页面 → "no page returned"）。with_limit(2) 实际只放行
        // 种子页：种子页 budget 2→1 放行，其后所有链接 budget==1 一律被判超预算而跳过。
        .with_limit(2)
        .with_proxies(proxies) // 经 PROXY_URL 让 Chrome 走代理（--proxy-server）
        .with_stealth(true) // 启用隐身模式，降低被检测为爬虫的概率
        .with_chrome_intercept(intercept) // 依赖 spider 的 chrome_intercept feature
        .with_wait_for_idle_network(Some(WaitForIdleNetwork::new(Some(Duration::from_secs(
            idle_wait_secs,
        ))))) // 等待网络空闲（JS 渲染完成）
        .with_screenshot(screenshot_config);

    if let Some(vp) = viewport {
        // Viewport::new(w, h) 会把 device_scale_factor 置 None，dpr 就此丢失；
        // 直接构造结构体才能把它带上（dpr=2 即 2x 截图）
        website.with_viewport(Some(spider::configuration::Viewport {
            width: vp.width,
            height: vp.height,
            device_scale_factor: Some(vp.dpr as f64),
            ..Default::default()
        }));
    }

    // 使用 deadline 的剩余时间作为 Chrome 执行超时，避免超出总预算
    let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
    if remaining.is_zero() {
        return Err(ScrapeError::Timeout(format!(
            "拿到无头浏览器锁时 {timeout_secs}s 预算已耗尽，没有时间留给页面加载"
        )));
    }

    let pages = tokio::time::timeout(remaining, async move {
        website.scrape().await;
        website.get_pages().map(|p| p.to_vec())
    })
    .await
    .map_err(|_| {
        ScrapeError::Timeout(format!(
            "无头浏览器加载 {url} 超时（HEADLESS_TIMEOUT_SECS={timeout_secs}, HEADLESS_IDLE_WAIT_SECS={idle_wait_secs}）"
        ))
    })?;

    // 取第一个页面；未返回任何页面视为失败
    let page = match pages {
        Some(p) if !p.is_empty() => p[0].clone(),
        _ => {
            return Err(ScrapeError::HeadlessFailed(format!(
                "无头浏览器抓 {url} 没有返回任何页面"
            )))
        }
    };

    let html = page.get_html().to_string();
    if html.is_empty() {
        return Err(ScrapeError::HeadlessFailed(format!(
            "无头浏览器抓 {url} 返回了空 HTML"
        )));
    }

    Ok(HeadlessCapture {
        html,
        screenshot_bytes: page.screenshot_bytes.clone(),
        status: page.status_code.as_u16(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 只截视口的 PNG 请求，供各集成用例复用
    fn shot(full_page: bool) -> ScreenshotOptions {
        ScreenshotOptions {
            enabled: true,
            full_page,
            format: ImageFormat::Png,
            quality: 80,
        }
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn headless_huaban_returns_rendered_html() {
        let result = capture_headless("https://huaban.com/", 60, 10, None, None).await;
        assert!(
            result.is_ok(),
            "capture_headless failed: {:?}",
            result.err()
        );
        let r = result.unwrap();
        assert!(!r.html.is_empty(), "html should not be empty");
        // 元数据由 extract 层解析，这里只验证拿到了渲染后的文档
        assert!(
            r.html.contains("<title"),
            "rendered html should contain a title tag"
        );
        assert_eq!(r.status, 200, "导航状态码应如实透出，而不是恒定 200");
        assert!(r.screenshot_bytes.is_none(), "未要求截图时不该产出截图");
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn headless_huaban_returns_screenshot_bytes() {
        let result = capture_headless("https://huaban.com/", 60, 10, None, Some(shot(true))).await;
        assert!(
            result.is_ok(),
            "capture_headless failed: {:?}",
            result.err()
        );
        let bytes = result
            .unwrap()
            .screenshot_bytes
            .expect("screenshot_bytes should not be None");
        assert!(
            bytes.len() > 10_240,
            "screenshot should be > 10KB, got {} bytes",
            bytes.len()
        );
    }

    /// 截图时必须放开样式与图片的拦截。
    ///
    /// 离线断言，不需要 Chrome —— 它守的是这段配置本身的语义，而不是"截图好不好看"
    /// （后者见 [`screenshot_still_renders_unstyled`]，那是另一个尚未定位的问题）。
    #[test]
    fn screenshot_requests_do_not_block_styles_or_images() {
        let with_shot = intercept_config(true);
        assert!(!with_shot.block_visuals, "截图不该拦 Image/Media/Font");
        assert!(!with_shot.block_stylesheets, "截图不该拦 CSS");
        assert!(with_shot.block_analytics, "埋点与观感无关，仍应拦掉");

        let plain = intercept_config(false);
        assert!(plain.block_visuals, "不截图时应保持默认拦截，省内存");
        assert!(plain.block_stylesheets);
    }

    /// **已知未修复的 bug，故意留一条说明在这里。**
    ///
    /// 无头截图出来的是一张**没有 CSS、没有图片、没有 Web 字体的裸 HTML**（宋体/Times、
    /// 默认项目符号、蓝色下划线链接）。原以为是 `RequestInterceptConfiguration` 默认拦掉
    /// 了 CSS/图片，实测证伪：
    ///
    /// | 配置 | stripe.com 1280×720 截图字节数 |
    /// |---|---|
    /// | `block_visuals=true, block_stylesheets=true`（原状） | 107264 |
    /// | 两者置 false（现状） | 107264 |
    /// | `RequestInterceptConfiguration::new(false)`，完全不拦 | 107264 |
    ///
    /// **逐字节相同**，说明渲染结果与拦截配置无关，样式压根没参与过这次渲染。真正的原因
    /// 还没定位，候选方向：截图时机早于样式应用；或 spider 的 `scrape()` 路径本身就不做
    /// 子资源加载。定位前不要写"截图已修复"。
    ///
    /// 这条用例本身**不做断言** —— 与其留一个通过了却什么都没保证的测试（老那条
    /// `> 10KB` 的断言正是如此：裸 HTML 也有 100KB，照过不误），不如把现场记在这里。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn screenshot_still_renders_unstyled() {
        let vp = Some(Viewport {
            width: 1280,
            height: 720,
            dpr: 1.0,
        });
        let bytes = capture_headless("https://stripe.com/", 60, 10, vp, Some(shot(false)))
            .await
            .expect("capture failed")
            .screenshot_bytes
            .expect("screenshot_bytes should not be None");
        // 把图落到磁盘供人眼确认，这是目前唯一可靠的判据
        let out = std::env::temp_dir().join("bookmarkify-screenshot-probe.png");
        std::fs::write(&out, &bytes).expect("write probe");
        eprintln!(
            "screenshot probe: {} bytes → {}",
            bytes.len(),
            out.display()
        );
    }

    /// fullPage 不该再是硬编码的 true —— 整页截图必然比单屏高，字节数也该更大。
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    #[ignore]
    async fn full_page_flag_actually_changes_the_capture() {
        let vp = Some(Viewport {
            width: 1280,
            height: 720,
            dpr: 1.0,
        });
        let viewport_only = capture_headless("https://stripe.com/", 60, 10, vp, Some(shot(false)))
            .await
            .expect("capture failed")
            .screenshot_bytes
            .expect("no bytes");
        let full = capture_headless("https://stripe.com/", 60, 10, vp, Some(shot(true)))
            .await
            .expect("capture failed")
            .screenshot_bytes
            .expect("no bytes");
        assert!(
            full.len() > viewport_only.len(),
            "整页截图({} bytes)应大于单屏截图({} bytes)，fullPage 可能又被忽略了",
            full.len(),
            viewport_only.len()
        );
    }
}
