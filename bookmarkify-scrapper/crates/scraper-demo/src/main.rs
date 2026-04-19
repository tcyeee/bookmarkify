// crates/scraper-demo/src/main.rs
use once_cell::sync::Lazy;
use regex::Regex;
use spider::configuration::{ScreenShotConfig, ScreenshotParams};
use spider::features::chrome_common::{RequestInterceptConfiguration, WaitForDelay};
use spider::website::Website;
use std::time::Duration;

/// 预编译的 `<title>` 标签正则表达式，避免每次调用时重复编译。
///
/// 匹配形如 `<title ...>内容</title>` 的标签，捕获组 1 为标签内的文本内容。
/// `(?i)` 表示大小写不敏感，兼容 `<TITLE>` 等变体。
static TITLE_RE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?i)<title[^>]*>([^<]+)</title>").unwrap()
});

/// 从原始 HTML 字符串中用正则表达式提取指定 meta 标签的 `content` 值。
///
/// 支持两种属性顺序：
/// - `<meta property="og:image" content="...">`
/// - `<meta content="..." property="og:image">`
///
/// 注意：此函数基于正则匹配，不做完整 HTML 解析，
/// 适合快速原型验证；生产环境请使用 `scraper-service` 中基于 DOM 的实现。
///
/// # 参数
/// - `html`：原始 HTML 字符串
/// - `property`：要查找的 `property` 或 `name` 属性值（如 `"og:image"`、`"description"`）
///
/// # 返回
/// 找到时返回 `Some(content 值)`，否则返回 `None`。
fn extract_meta(html: &str, property: &str) -> Option<String> {
    let pattern = format!(
        r#"(?:property|name)=["\']{}["\'][^>]*content=["\']([^"\']+)["\']|content=["\']([^"\']+)["\'][^>]*(?:property|name)=["\']{}["\']"#,
        property, property
    );
    let re = Regex::new(&pattern).ok()?;
    re.captures(html)
        .and_then(|c| c.get(1).or(c.get(2)))
        .map(|m| m.as_str().to_string())
}

/// 从原始 HTML 字符串中提取 `<title>` 标签的文本内容。
///
/// 使用预编译的 [`TITLE_RE`] 正则表达式匹配，并去除首尾空白。
///
/// # 参数
/// - `html`：原始 HTML 字符串
///
/// # 返回
/// 找到时返回 `Some(标题文本)`，否则返回 `None`。
fn extract_title(html: &str) -> Option<String> {
    TITLE_RE.captures(html)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().trim().to_string())
}

/// 从 URL 字符串中提取域名，并将 `.` 替换为 `_`，用于生成文件名。
///
/// 例如：`"https://www.zhihu.com/hot"` → `"www_zhihu_com"`
///
/// # 参数
/// - `url`：目标 URL 字符串
///
/// # 返回
/// 域名部分（去掉协议前缀），`.` 替换为 `_`。若解析失败则返回 `"unknown"`。
fn domain_from_url(url: &str) -> String {
    url.trim_start_matches("https://")
        .trim_start_matches("http://")
        .split('/')
        .next()
        .unwrap_or("unknown")
        .replace('.', "_")
}

/// 使用无头 Chrome 抓取指定 URL 的页面元数据和截图，返回 JSON 格式的结果。
///
/// 流程：
/// 1. 启动带隐身模式和请求拦截的无头浏览器
/// 2. 等待最多 8 秒让页面 JS 渲染完成
/// 3. 提取 `<title>`、`description`、`og:image` 等元数据
/// 4. 将截图字节保存到 `screenshots/{domain}.png`
/// 5. 检测标题是否包含反爬关键词（robot、验证、captcha）
///
/// # 参数
/// - `url`：目标页面 URL 字符串
///
/// # 返回
/// 包含以下字段的 JSON 对象：
/// - `url`：目标 URL
/// - `title`：页面标题（`null` 若未获取到）
/// - `description`：页面描述（优先 meta description，回退 og:description）
/// - `og_image`：OG 图片 URL
/// - `screenshot`：截图保存路径（`null` 若截图失败）
/// - `anti_crawler_bypassed`：是否成功绕过反爬（标题不含机器人/验证关键词）
/// - `error`：仅在无法获取页面时出现，值为 `"no page"`
async fn scrape_url(url: &str) -> serde_json::Value {
    println!("\n>>> 开始抓取: {url}");

    // 配置截图：在内存中返回字节，不写入磁盘（由我们手动保存）
    let screenshot_config = ScreenShotConfig::new(
        ScreenshotParams::new(Default::default(), Some(true), None),
        true,  // bytes: return screenshot bytes in page
        false, // save: don't use spider's auto-save (we save manually)
        None,
    );

    let mut website = Website::new(url);
    website
        .with_stealth(true) // 启用隐身模式，模拟真实浏览器指纹
        .with_chrome_intercept(RequestInterceptConfiguration::new(true)) // 拦截广告和追踪请求
        .with_wait_for_delay(Some(WaitForDelay {
            timeout: Some(Duration::from_secs(8)), // 等待 JS 渲染，最长 8 秒
        }))
        .with_screenshot(Some(screenshot_config));

    website.scrape().await;

    let pages = website.get_pages();
    let page = match pages {
        Some(pages) if !pages.is_empty() => pages[0].clone(),
        _ => {
            eprintln!("未获取到页面: {url}");
            return serde_json::json!({ "url": url, "error": "no page" });
        }
    };

    let html = page.get_html().to_string();

    // 将截图字节保存到 screenshots/{domain}.png
    let domain = domain_from_url(url);
    let screenshot_path = format!("screenshots/{domain}.png");
    std::fs::create_dir_all("screenshots").ok();

    let screenshot_result = match &page.screenshot_bytes {
        Some(bytes) if !bytes.is_empty() => {
            match std::fs::write(&screenshot_path, bytes) {
                Ok(_) => {
                    println!("截图已保存: {} ({} bytes)", screenshot_path, bytes.len());
                    Some(screenshot_path.clone())
                }
                Err(e) => {
                    eprintln!("截图保存失败: {e}");
                    None
                }
            }
        }
        _ => {
            eprintln!("未获取到截图字节: {url}");
            None
        }
    };

    let title = extract_title(&html);
    // 判断是否绕过了反爬：标题存在且不含反爬关键词
    let anti_crawler_bypassed = title.as_deref().map(|t| {
        let t_lower = t.to_lowercase();
        !t_lower.contains("robot") && !t_lower.contains("验证") && !t_lower.contains("captcha")
    }).unwrap_or(false);

    serde_json::json!({
        "url": url,
        "title": title,
        "description": extract_meta(&html, "description")
            .or_else(|| extract_meta(&html, "og:description")),
        "og_image": extract_meta(&html, "og:image"),
        "screenshot": screenshot_result,
        "anti_crawler_bypassed": anti_crawler_bypassed,
    })
}

/// Demo 程序入口：依次抓取预设的目标 URL 列表，并将结果以格式化 JSON 输出到控制台。
///
/// 目标网站均为重度 JS 渲染的页面（花瓣网、知乎），用于验证无头浏览器
/// 和反爬绕过能力是否正常工作（M0 阶段验证）。
#[tokio::main]
async fn main() {
    let targets = [
        "https://huaban.com/",
        "https://www.zhihu.com/",
    ];

    let mut results = Vec::new();
    for url in targets {
        let result = scrape_url(url).await;
        results.push(result);
    }

    println!("\n\n===== M0 验证结果 =====");
    println!("{}", serde_json::to_string_pretty(&results).unwrap());
}
