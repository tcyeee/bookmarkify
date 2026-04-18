// crates/scraper-demo/src/main.rs
use once_cell::sync::Lazy;
use regex::Regex;
use spider::configuration::{ScreenShotConfig, ScreenshotParams};
use spider::features::chrome_common::{RequestInterceptConfiguration, WaitForDelay};
use spider::website::Website;
use std::time::Duration;

static TITLE_RE: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?i)<title[^>]*>([^<]+)</title>").unwrap()
});

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

fn extract_title(html: &str) -> Option<String> {
    TITLE_RE.captures(html)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().trim().to_string())
}

fn domain_from_url(url: &str) -> String {
    url.trim_start_matches("https://")
        .trim_start_matches("http://")
        .split('/')
        .next()
        .unwrap_or("unknown")
        .replace('.', "_")
}

async fn scrape_url(url: &str) -> serde_json::Value {
    println!("\n>>> 开始抓取: {url}");

    let screenshot_config = ScreenShotConfig::new(
        ScreenshotParams::new(Default::default(), Some(true), None),
        true,  // bytes: return screenshot bytes in page
        false, // save: don't use spider's auto-save (we save manually)
        None,
    );

    let mut website = Website::new(url);
    website
        .with_stealth(true)
        .with_chrome_intercept(RequestInterceptConfiguration::new(true))
        .with_wait_for_delay(Some(WaitForDelay {
            timeout: Some(Duration::from_secs(8)),
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

    // Save screenshot bytes to screenshots/{domain}.png
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
