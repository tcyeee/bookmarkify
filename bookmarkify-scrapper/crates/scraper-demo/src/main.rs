// crates/scraper-demo/src/main.rs
use spider::features::chrome_common::{RequestInterceptConfiguration, WaitForDelay};
use spider::website::Website;
use std::time::Duration;

fn extract_meta(html: &str, property: &str) -> Option<String> {
    let pattern = format!(
        r#"(?:property|name)=["\']{}["\'][^>]*content=["\']([^"\']+)["\']|content=["\']([^"\']+)["\'][^>]*(?:property|name)=["\']{}["\']"#,
        property, property
    );
    let re = regex::Regex::new(&pattern).ok()?;
    re.captures(html)
        .and_then(|c| c.get(1).or(c.get(2)))
        .map(|m| m.as_str().to_string())
}

fn extract_title(html: &str) -> Option<String> {
    let re = regex::Regex::new(r"<title[^>]*>([^<]+)</title>").ok()?;
    re.captures(html)
        .and_then(|c| c.get(1))
        .map(|m| m.as_str().trim().to_string())
}

#[tokio::main]
async fn main() {
    let url = "https://www.zhihu.com/";

    let mut website = Website::new(url);
    website
        .with_stealth(true)
        .with_chrome_intercept(RequestInterceptConfiguration::new(true))
        .with_wait_for_delay(Some(WaitForDelay {
            timeout: Some(Duration::from_secs(8)),
        }));

    website.scrape().await;

    let pages = website.get_pages();
    let html = match pages {
        Some(pages) if !pages.is_empty() => pages[0].get_html().to_string(),
        _ => {
            eprintln!("未获取到页面");
            return;
        }
    };

    let result = serde_json::json!({
        "url": url,
        "title": extract_title(&html),
        "description": extract_meta(&html, "description").or_else(|| extract_meta(&html, "og:description")),
        "og_image": extract_meta(&html, "og:image"),
    });

    println!("{}", serde_json::to_string_pretty(&result).unwrap());
}
