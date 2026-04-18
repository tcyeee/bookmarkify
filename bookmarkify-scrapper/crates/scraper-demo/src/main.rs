// crates/scraper-demo/src/main.rs
use spider::features::chrome_common::{RequestInterceptConfiguration, WaitForDelay};
use spider::website::Website;
use std::time::Duration;

#[tokio::main]
async fn main() {
    let url = "https://www.zhihu.com/";

    println!("抓取: {url}");

    let mut website = Website::new(url);
    website
        .with_stealth(true)
        .with_chrome_intercept(RequestInterceptConfiguration::new(true))
        .with_wait_for_delay(Some(WaitForDelay {
            timeout: Some(Duration::from_secs(8)),
        }));

    website.scrape().await;

    let pages = website.get_pages();
    match pages {
        Some(pages) if !pages.is_empty() => {
            let html = pages[0].get_html();
            println!("HTML 长度: {} bytes", html.len());
            println!("HTML 前 300 字符:\n{}", &html[..html.len().min(300)]);
        }
        _ => eprintln!("未获取到页面"),
    }
}
