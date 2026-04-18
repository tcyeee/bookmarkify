use scraper::{Html, Selector};
use serde::Serialize;

#[derive(Debug, Serialize, PartialEq)]
pub struct ScrapeResult {
    pub title: Option<String>,
    pub description: Option<String>,
    pub image: Option<String>,
    pub favicon: Option<String>,
    pub source: String,
}

#[derive(Debug)]
pub enum ScrapeError {
    InvalidUrl,
    Timeout,
    FetchFailed(String),
}

pub fn meta_property(document: &Html, property: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[property="{}"]"#, property)).ok()?;
    document
        .select(&selector)
        .next()
        .and_then(|e| e.value().attr("content"))
        .map(str::to_string)
}

pub fn meta_name(document: &Html, name: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[name="{}"]"#, name)).ok()?;
    document
        .select(&selector)
        .next()
        .and_then(|e| e.value().attr("content"))
        .map(str::to_string)
}

pub fn extract_title(document: &Html) -> Option<String> {
    let selector = Selector::parse("title").ok()?;
    document
        .select(&selector)
        .next()
        .map(|e| e.text().collect::<String>().trim().to_string())
        .filter(|s| !s.is_empty())
}

pub fn extract_favicon(document: &Html, base_url: &reqwest::Url) -> Option<String> {
    let selector = Selector::parse(r#"link[rel~="icon"]"#).ok()?;
    let href = document
        .select(&selector)
        .next()
        .and_then(|e| e.value().attr("href"));

    let favicon_url = match href {
        Some(h) => base_url.join(h).ok()?,
        None => base_url.join("/favicon.ico").ok()?,
    };
    Some(favicon_url.to_string())
}

pub fn extract_json_ld(document: &Html) -> Option<(Option<String>, Option<String>, Option<String>)> {
    let selector = Selector::parse(r#"script[type="application/ld+json"]"#).ok()?;
    for element in document.select(&selector) {
        let text = element.text().collect::<String>();
        if let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) {
            let title = json.get("name").and_then(|v| v.as_str()).map(String::from);
            if title.is_none() {
                continue;
            }
            let desc = json.get("description").and_then(|v| v.as_str()).map(String::from);
            let image = json
                .get("image")
                .and_then(|v| v.as_str().map(String::from).or_else(|| {
                    v.get("url").and_then(|u| u.as_str()).map(String::from)
                }));
            return Some((title, desc, image));
        }
    }
    None
}

pub fn parse_metadata(html: &str, base_url: &reqwest::Url) -> ScrapeResult {
    let document = Html::parse_document(html);

    // OG tags
    if let Some(title) = meta_property(&document, "og:title") {
        return ScrapeResult {
            title: Some(title),
            description: meta_property(&document, "og:description")
                .or_else(|| meta_name(&document, "description")),
            image: meta_property(&document, "og:image"),
            favicon: extract_favicon(&document, base_url),
            source: "og".to_string(),
        };
    }

    // Twitter Card
    if let Some(title) = meta_name(&document, "twitter:title") {
        return ScrapeResult {
            title: Some(title),
            description: meta_name(&document, "twitter:description")
                .or_else(|| meta_name(&document, "description")),
            image: meta_name(&document, "twitter:image"),
            favicon: extract_favicon(&document, base_url),
            source: "twitter_card".to_string(),
        };
    }

    // JSON-LD
    if let Some((title, desc, image)) = extract_json_ld(&document) {
        return ScrapeResult {
            title,
            description: desc.or_else(|| meta_name(&document, "description")),
            image,
            favicon: extract_favicon(&document, base_url),
            source: "json_ld".to_string(),
        };
    }

    // HTML fallback
    ScrapeResult {
        title: extract_title(&document),
        description: meta_name(&document, "description"),
        image: None,
        favicon: extract_favicon(&document, base_url),
        source: "html".to_string(),
    }
}

pub async fn scrape(url: &str, client: &reqwest::Client) -> Result<ScrapeResult, ScrapeError> {
    let parsed = reqwest::Url::parse(url).map_err(|_| ScrapeError::InvalidUrl)?;

    let response = client
        .get(url)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) \
             AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        )
        .send()
        .await
        .map_err(|e| {
            if e.is_timeout() {
                ScrapeError::Timeout
            } else {
                ScrapeError::FetchFailed(e.to_string())
            }
        })?
        .error_for_status()
        .map_err(|e| ScrapeError::FetchFailed(e.to_string()))?;

    let body = response
        .text()
        .await
        .map_err(|e| ScrapeError::FetchFailed(e.to_string()))?;

    Ok(parse_metadata(&body, &parsed))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn doc(html: &str) -> Html {
        Html::parse_document(html)
    }

    fn base() -> reqwest::Url {
        reqwest::Url::parse("https://example.com").unwrap()
    }

    #[test]
    fn meta_property_finds_og_title() {
        let d = doc(r#"<html><head><meta property="og:title" content="My Title"/></head></html>"#);
        assert_eq!(meta_property(&d, "og:title"), Some("My Title".to_string()));
    }

    #[test]
    fn meta_property_returns_none_when_absent() {
        let d = doc("<html><head></head></html>");
        assert_eq!(meta_property(&d, "og:title"), None);
    }

    #[test]
    fn meta_name_finds_description() {
        let d = doc(r#"<html><head><meta name="description" content="A desc"/></head></html>"#);
        assert_eq!(meta_name(&d, "description"), Some("A desc".to_string()));
    }

    #[test]
    fn extract_title_gets_title_tag() {
        let d = doc("<html><head><title>Hello World</title></head></html>");
        assert_eq!(extract_title(&d), Some("Hello World".to_string()));
    }

    #[test]
    fn extract_title_trims_whitespace() {
        let d = doc("<html><head><title>  Trimmed  </title></head></html>");
        assert_eq!(extract_title(&d), Some("Trimmed".to_string()));
    }

    #[test]
    fn extract_favicon_finds_link_icon() {
        let d = doc(r#"<html><head><link rel="icon" href="/favicon.ico"/></head></html>"#);
        assert_eq!(extract_favicon(&d, &base()), Some("https://example.com/favicon.ico".to_string()));
    }

    #[test]
    fn extract_favicon_falls_back_to_root() {
        let d = doc("<html><head></head></html>");
        assert_eq!(extract_favicon(&d, &base()), Some("https://example.com/favicon.ico".to_string()));
    }

    #[test]
    fn extract_json_ld_extracts_name() {
        let d = doc(r#"<html><head><script type="application/ld+json">{"@type":"Article","name":"LD Title","description":"LD Desc"}</script></head></html>"#);
        let result = extract_json_ld(&d);
        assert!(result.is_some());
        let (title, desc, _) = result.unwrap();
        assert_eq!(title, Some("LD Title".to_string()));
        assert_eq!(desc, Some("LD Desc".to_string()));
    }

    #[test]
    fn parse_metadata_prefers_og_over_twitter() {
        let html = r#"<html><head>
            <meta property="og:title" content="OG Title"/>
            <meta name="twitter:title" content="TW Title"/>
        </head></html>"#;
        let result = parse_metadata(html, &base());
        assert_eq!(result.title, Some("OG Title".to_string()));
        assert_eq!(result.source, "og");
    }

    #[test]
    fn parse_metadata_falls_back_to_html() {
        let html = "<html><head><title>Plain Title</title></head></html>";
        let result = parse_metadata(html, &base());
        assert_eq!(result.title, Some("Plain Title".to_string()));
        assert_eq!(result.source, "html");
    }
}
