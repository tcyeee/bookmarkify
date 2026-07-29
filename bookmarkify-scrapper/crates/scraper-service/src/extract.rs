//! HTML → 契约类型的**纯提取层**：不发任何网络请求，全部可离线测试。
//!
//! 与被它取代的 `scraper::parse_metadata` 相比有两处结构性差异：
//!
//! 1. **出处下沉到字段级。** 旧实现按 og / twitter_card / json_ld / html 分成四个互斥
//!    分支，整体上报一个 `source`；但分支内部本来就会混用来源（OG 分支里
//!    `og:description` 缺失时会回落到 `meta[name=description]`），那个 `source` 一直在
//!    说谎。这里改成逐字段独立回落，各自记录 [`MetaSource`]。
//!
//! 2. **图片全量返回，不做取舍。** 旧实现只挑"最大的那个 favicon"和"一个 logo"。这里
//!    把页面声明的每一张都返回，并标注 [`AssetExtractor`]（出处，事实），用途判定
//!    (role) 留给调用方 —— 见 `contract.rs` 模块文档。

use crate::contract::{
    Alternate, AntiCrawler, Asset, AssetExtractor, DeclaredAttrs, ExtractOptions, Feed,
    MetaExtractor, MetaSource, PageMeta,
};
use scraper::{Html, Selector};
use std::collections::BTreeMap;

/// 纯 HTML 提取的产物。网络相关字段（真实尺寸、hash、storageKey）由上层流水线补齐。
#[derive(Debug, Default)]
pub struct Extracted {
    pub meta: PageMeta,
    /// 页面声明的全部图片，仅填了声明类字段
    pub assets: Vec<Asset>,
    /// `<link rel="manifest">` 的绝对地址，供上层决定是否再发一次请求
    pub manifest_url: Option<String>,
    pub jsonld: Vec<serde_json::Value>,
    pub opengraph: BTreeMap<String, String>,
    pub twitter: BTreeMap<String, String>,
    pub feeds: Vec<Feed>,
    pub alternates: Vec<Alternate>,
    pub anti_crawler: Option<AntiCrawler>,
}

/// 解析 HTML，按 [`ExtractOptions`] 的开关产出各块内容。
///
/// `base_url` 应为跟完重定向后的最终 URL —— 所有相对路径以它为基准解析。
pub fn extract_page(html: &str, base_url: &reqwest::Url, opts: &ExtractOptions) -> Extracted {
    let doc = Html::parse_document(html);

    // JSON-LD 被 meta 和 assets 两处复用，只要其中之一开着就得先解析
    let jsonld_nodes = if opts.jsonld || opts.meta || opts.assets {
        collect_jsonld(&doc)
    } else {
        Vec::new()
    };

    let opengraph = if opts.opengraph || opts.meta {
        collect_prefixed(&doc, "property", "og:")
    } else {
        BTreeMap::new()
    };
    let twitter = if opts.twitter || opts.meta {
        collect_prefixed(&doc, "name", "twitter:")
    } else {
        BTreeMap::new()
    };

    let meta = if opts.meta {
        extract_meta(&doc, base_url, &opengraph, &twitter, &jsonld_nodes)
    } else {
        PageMeta::default()
    };

    let assets = if opts.assets {
        extract_assets(&doc, base_url, &opengraph, &twitter, &jsonld_nodes)
    } else {
        Vec::new()
    };

    Extracted {
        meta,
        assets,
        manifest_url: if opts.manifest {
            find_manifest_url(&doc, base_url)
        } else {
            None
        },
        jsonld: if opts.jsonld {
            jsonld_nodes
        } else {
            Vec::new()
        },
        opengraph: if opts.opengraph {
            opengraph
        } else {
            BTreeMap::new()
        },
        twitter: if opts.twitter {
            twitter
        } else {
            BTreeMap::new()
        },
        feeds: if opts.feeds {
            extract_feeds(&doc, base_url)
        } else {
            Vec::new()
        },
        alternates: if opts.alternates {
            extract_alternates(&doc, base_url)
        } else {
            Vec::new()
        },
        anti_crawler: detect_anti_crawler(&doc, html),
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 元数据
// ─────────────────────────────────────────────────────────────────────────────

/// 逐字段独立回落并记录出处的累加器。
struct MetaBuilder {
    meta: PageMeta,
}

impl MetaBuilder {
    fn new() -> Self {
        Self {
            meta: PageMeta::default(),
        }
    }

    /// 按候选顺序取第一个非空值，并把命中的出处记进 `sources[field]`。
    ///
    /// `field` 必须是 [`PageMeta`] 的 camelCase 字段名 —— 契约里 `sources` 的键与字段
    /// 一一对应，调用方靠它反查"这个值哪来的"。
    fn fill(
        &mut self,
        field: &str,
        candidates: Vec<(Option<String>, MetaExtractor, &str)>,
    ) -> Option<String> {
        for (value, extractor, raw_key) in candidates {
            let value = value
                .map(|v| v.trim().to_string())
                .filter(|v| !v.is_empty());
            if let Some(v) = value {
                self.meta.sources.insert(
                    field.to_string(),
                    MetaSource {
                        extractor,
                        raw_key: Some(raw_key.to_string()),
                    },
                );
                return Some(v);
            }
        }
        None
    }
}

fn extract_meta(
    doc: &Html,
    base_url: &reqwest::Url,
    og: &BTreeMap<String, String>,
    tw: &BTreeMap<String, String>,
    jsonld: &[serde_json::Value],
) -> PageMeta {
    let mut b = MetaBuilder::new();

    let jl = |key: &str| jsonld_str(jsonld, key);

    let title = b.fill(
        "title",
        vec![
            (og.get("title").cloned(), MetaExtractor::Og, "og:title"),
            (
                tw.get("title").cloned(),
                MetaExtractor::TwitterCard,
                "twitter:title",
            ),
            (jl("name"), MetaExtractor::JsonLd, "name"),
            (text_of(doc, "title"), MetaExtractor::TitleTag, "title"),
        ],
    );

    // 这一行就是旧契约说谎的地方：og:title 命中但 og:description 缺失时，
    // description 实际来自 meta[name=description]，旧实现却整体上报 source="og"。
    let description = b.fill(
        "description",
        vec![
            (
                og.get("description").cloned(),
                MetaExtractor::Og,
                "og:description",
            ),
            (
                tw.get("description").cloned(),
                MetaExtractor::TwitterCard,
                "twitter:description",
            ),
            (jl("description"), MetaExtractor::JsonLd, "description"),
            (
                meta_name(doc, "description"),
                MetaExtractor::MetaName,
                "description",
            ),
        ],
    );

    let site_name = b.fill(
        "siteName",
        vec![
            (
                og.get("site_name").cloned(),
                MetaExtractor::Og,
                "og:site_name",
            ),
            (jl("publisher"), MetaExtractor::JsonLd, "publisher"),
        ],
    );

    let canonical_url = b.fill(
        "canonicalUrl",
        vec![(
            link_href(doc, base_url, r#"link[rel="canonical"]"#),
            MetaExtractor::LinkTag,
            "canonical",
        )],
    );

    let lang = b.fill(
        "lang",
        vec![(
            attr_of(doc, "html", "lang"),
            MetaExtractor::HtmlAttr,
            "lang",
        )],
    );

    let theme_color = b.fill(
        "themeColor",
        vec![(
            meta_name(doc, "theme-color"),
            MetaExtractor::MetaName,
            "theme-color",
        )],
    );

    let author = b.fill(
        "author",
        vec![
            (meta_name(doc, "author"), MetaExtractor::MetaName, "author"),
            (jl("author"), MetaExtractor::JsonLd, "author"),
        ],
    );

    let published_at = b.fill(
        "publishedAt",
        vec![
            (
                og.get("article:published_time").cloned(),
                MetaExtractor::Og,
                "article:published_time",
            ),
            (
                meta_property(doc, "article:published_time"),
                MetaExtractor::Og,
                "article:published_time",
            ),
            (jl("datePublished"), MetaExtractor::JsonLd, "datePublished"),
        ],
    );

    let robots = b.fill(
        "robots",
        vec![(meta_name(doc, "robots"), MetaExtractor::MetaName, "robots")],
    );

    let keywords_raw = b.fill(
        "keywords",
        vec![(
            meta_name(doc, "keywords"),
            MetaExtractor::MetaName,
            "keywords",
        )],
    );
    let keywords = keywords_raw
        .map(|s| {
            s.split(',')
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty())
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    if keywords.is_empty() {
        b.meta.sources.remove("keywords");
    }

    PageMeta {
        title,
        description,
        site_name,
        // short_name 只可能来自 manifest，由上层抓到 manifest 后回填
        short_name: None,
        canonical_url,
        lang,
        theme_color,
        author,
        published_at,
        robots,
        keywords,
        sources: b.meta.sources,
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 图片资源
// ─────────────────────────────────────────────────────────────────────────────

/// 收集页面声明的全部图片。
///
/// 刻意**不做择优**：同一站点的 `link[rel=icon]`、`apple-touch-icon`、`og:image` 会各自
/// 独立成条。挑哪张、算什么用途，是调用方的策略。
fn extract_assets(
    doc: &Html,
    base_url: &reqwest::Url,
    og: &BTreeMap<String, String>,
    tw: &BTreeMap<String, String>,
    jsonld: &[serde_json::Value],
) -> Vec<Asset> {
    let mut out: Vec<Asset> = Vec::new();

    // link 系：rel 可能是空格分隔的多值（如 rel="shortcut icon"），用 ~= 匹配
    let link_specs: &[(&str, AssetExtractor)] = &[
        (r#"link[rel~="icon"]"#, AssetExtractor::LinkIcon),
        (
            r#"link[rel~="apple-touch-icon"]"#,
            AssetExtractor::AppleTouchIcon,
        ),
        (
            r#"link[rel~="apple-touch-icon-precomposed"]"#,
            AssetExtractor::AppleTouchIcon,
        ),
        (r#"link[rel~="mask-icon"]"#, AssetExtractor::LinkMaskIcon),
    ];
    let mut saw_link_icon = false;
    for (sel, extractor) in link_specs {
        let Ok(selector) = Selector::parse(sel) else {
            continue;
        };
        for el in doc.select(&selector) {
            let v = el.value();
            // rel="apple-touch-icon" 也会被 rel~="icon" 匹配到吗？不会 —— ~= 是整词匹配，
            // "apple-touch-icon" 是一个词，不含独立的 "icon" 词。
            let Some(href) = v.attr("href").filter(|h| !h.trim().is_empty()) else {
                continue;
            };
            if *extractor == AssetExtractor::LinkIcon {
                saw_link_icon = true;
            }
            push_asset(
                &mut out,
                base_url,
                *extractor,
                href,
                DeclaredAttrs {
                    rel: v.attr("rel").map(str::to_string),
                    sizes: v.attr("sizes").map(str::to_string),
                    type_: v.attr("type").map(str::to_string),
                    media: v.attr("media").map(str::to_string),
                    purpose: None,
                },
            );
        }
    }

    // msapplication-TileImage
    if let Some(src) = meta_name(doc, "msapplication-TileImage") {
        push_asset(
            &mut out,
            base_url,
            AssetExtractor::MsTileImage,
            &src,
            DeclaredAttrs::default(),
        );
    }

    // og:image —— og:image:url 是等价写法，两者都收
    for key in ["image", "image:url", "image:secure_url"] {
        if let Some(src) = og.get(key) {
            push_asset(
                &mut out,
                base_url,
                AssetExtractor::OgImage,
                src,
                DeclaredAttrs::default(),
            );
        }
    }
    if let Some(src) = tw.get("image") {
        push_asset(
            &mut out,
            base_url,
            AssetExtractor::TwitterImage,
            src,
            DeclaredAttrs::default(),
        );
    }

    // JSON-LD：Organization.logo 是唯一语义明确的"品牌 LOGO"，与泛用的 image 区分开
    for node in jsonld {
        if let Some(src) = json_url(node.get("logo")) {
            push_asset(
                &mut out,
                base_url,
                AssetExtractor::JsonLdOrgLogo,
                &src,
                DeclaredAttrs::default(),
            );
        }
        if let Some(src) = json_url(node.get("image")) {
            push_asset(
                &mut out,
                base_url,
                AssetExtractor::JsonLdImage,
                &src,
                DeclaredAttrs::default(),
            );
        }
    }

    // 页面一个 icon 都没声明时，才按约定探一次 /favicon.ico
    if !saw_link_icon {
        push_asset(
            &mut out,
            base_url,
            AssetExtractor::FaviconIcoFallback,
            "/favicon.ico",
            DeclaredAttrs::default(),
        );
    }

    out
}

/// 追加一条资产；(extractor, resolvedUrl) 相同的重复声明只保留第一条。
fn push_asset(
    out: &mut Vec<Asset>,
    base_url: &reqwest::Url,
    extractor: AssetExtractor,
    href: &str,
    declared: DeclaredAttrs,
) {
    let href = href.trim();
    if href.is_empty() {
        return;
    }
    // data: URI 直接是内容本身，没有可探测的远端资源，跳过
    if href.starts_with("data:") {
        return;
    }
    let Ok(resolved) = base_url.join(href) else {
        return;
    };
    let resolved_url = resolved.to_string();

    if out
        .iter()
        .any(|a| a.extractor == extractor && a.resolved_url == resolved_url)
    {
        return;
    }

    let is_vector = Some(
        declared.type_.as_deref().is_some_and(|t| t.contains("svg"))
            || resolved.path().to_ascii_lowercase().ends_with(".svg"),
    );

    out.push(Asset {
        extractor,
        declared,
        origin_url: href.to_string(),
        resolved_url,
        width: None,
        height: None,
        byte_size: None,
        mime: None,
        is_vector,
        content_hash: None,
        storage_key: None,
        data_url: None,
        error: None,
    });
}

// ─────────────────────────────────────────────────────────────────────────────
// 其余块
// ─────────────────────────────────────────────────────────────────────────────

fn find_manifest_url(doc: &Html, base_url: &reqwest::Url) -> Option<String> {
    link_href(doc, base_url, r#"link[rel~="manifest"]"#)
}

fn extract_feeds(doc: &Html, base_url: &reqwest::Url) -> Vec<Feed> {
    let Ok(selector) = Selector::parse(r#"link[rel~="alternate"]"#) else {
        return Vec::new();
    };
    doc.select(&selector)
        .filter_map(|el| {
            let v = el.value();
            let mime = v.attr("type")?;
            if !(mime.contains("rss") || mime.contains("atom")) {
                return None;
            }
            let href = v.attr("href")?;
            Some(Feed {
                url: base_url.join(href).ok()?.to_string(),
                title: v.attr("title").map(str::to_string),
                mime: Some(mime.to_string()),
            })
        })
        .collect()
}

fn extract_alternates(doc: &Html, base_url: &reqwest::Url) -> Vec<Alternate> {
    let Ok(selector) = Selector::parse(r#"link[rel~="alternate"]"#) else {
        return Vec::new();
    };
    doc.select(&selector)
        .filter_map(|el| {
            let v = el.value();
            // 订阅源走 feeds，这里只要多语言/多端替代
            if v.attr("hreflang").is_none() && v.attr("media").is_none() {
                return None;
            }
            let href = v.attr("href")?;
            Some(Alternate {
                url: base_url.join(href).ok()?.to_string(),
                hreflang: v.attr("hreflang").map(str::to_string),
                media: v.attr("media").map(str::to_string),
            })
        })
        .collect()
}

/// 反爬 / WAF 挑战页检测。命中时调用方应把 [`PageMeta`] 的内容视为不可靠。
fn detect_anti_crawler(doc: &Html, html: &str) -> Option<AntiCrawler> {
    const TITLE_SIGNALS: &[&str] = &[
        "just a moment",
        "attention required",
        "access denied",
        "security check",
        "请稍候",
        "人机验证",
        "安全验证",
    ];
    if let Some(title) = text_of(doc, "title") {
        let lower = title.to_lowercase();
        if let Some(hit) = TITLE_SIGNALS.iter().find(|s| lower.contains(**s)) {
            return Some(AntiCrawler {
                detected: true,
                signal: Some(format!("title:{hit}")),
            });
        }
    }
    const BODY_SIGNALS: &[&str] = &[
        "cf-chl",
        "cf_chl_opt",
        "/cdn-cgi/challenge-platform",
        "_Incapsula_",
    ];
    if let Some(hit) = BODY_SIGNALS.iter().find(|s| html.contains(**s)) {
        return Some(AntiCrawler {
            detected: true,
            signal: Some((*hit).to_string()),
        });
    }
    None
}

// ─────────────────────────────────────────────────────────────────────────────
// 小工具
// ─────────────────────────────────────────────────────────────────────────────

/// 收集某个前缀下的全部 meta 键值对，键已去掉前缀（`og:title` → `title`）。
/// 同名重复时保留第一个，与浏览器行为一致。
fn collect_prefixed(doc: &Html, attr: &str, prefix: &str) -> BTreeMap<String, String> {
    let Ok(selector) = Selector::parse(&format!(r#"meta[{attr}^="{prefix}"]"#)) else {
        return BTreeMap::new();
    };
    let mut map = BTreeMap::new();
    for el in doc.select(&selector) {
        let v = el.value();
        let (Some(key), Some(content)) = (v.attr(attr), v.attr("content")) else {
            continue;
        };
        let Some(stripped) = key.strip_prefix(prefix) else {
            continue;
        };
        if stripped.is_empty() || content.trim().is_empty() {
            continue;
        }
        map.entry(stripped.to_string())
            .or_insert_with(|| content.trim().to_string());
    }
    map
}

/// 收集全部 JSON-LD 节点；`@graph` 内的子节点会被展开成同级节点。
fn collect_jsonld(doc: &Html) -> Vec<serde_json::Value> {
    let Ok(selector) = Selector::parse(r#"script[type="application/ld+json"]"#) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for el in doc.select(&selector) {
        let text = el.text().collect::<String>();
        let Ok(json) = serde_json::from_str::<serde_json::Value>(&text) else {
            continue;
        };
        if let Some(graph) = json.get("@graph").and_then(|v| v.as_array()) {
            out.extend(graph.iter().cloned());
        }
        match json {
            serde_json::Value::Array(items) => out.extend(items),
            other => out.push(other),
        }
    }
    out
}

/// 在全部 JSON-LD 节点里找第一个该键的字符串值。
/// 值可能是裸串、`{ "name": ... }` 或 `{ "url": ... }`（如 author/publisher）。
fn jsonld_str(nodes: &[serde_json::Value], key: &str) -> Option<String> {
    nodes.iter().find_map(|n| {
        n.get(key).and_then(|v| {
            v.as_str()
                .map(String::from)
                .or_else(|| v.get("name").and_then(|x| x.as_str()).map(String::from))
                .or_else(|| v.get("url").and_then(|x| x.as_str()).map(String::from))
        })
    })
}

/// JSON-LD 里的图片值：裸串 / `{url}` / 数组取首个。
fn json_url(v: Option<&serde_json::Value>) -> Option<String> {
    let v = v?;
    if let Some(s) = v.as_str() {
        return Some(s.to_string());
    }
    if let Some(u) = v.get("url").and_then(|u| u.as_str()) {
        return Some(u.to_string());
    }
    v.as_array()?.first().and_then(|f| json_url(Some(f)))
}

fn meta_name(doc: &Html, name: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[name="{name}"]"#)).ok()?;
    doc.select(&selector)
        .next()?
        .value()
        .attr("content")
        .map(str::to_string)
}

fn meta_property(doc: &Html, property: &str) -> Option<String> {
    let selector = Selector::parse(&format!(r#"meta[property="{property}"]"#)).ok()?;
    doc.select(&selector)
        .next()?
        .value()
        .attr("content")
        .map(str::to_string)
}

fn text_of(doc: &Html, sel: &str) -> Option<String> {
    let selector = Selector::parse(sel).ok()?;
    doc.select(&selector)
        .next()
        .map(|e| e.text().collect::<String>().trim().to_string())
        .filter(|s| !s.is_empty())
}

fn attr_of(doc: &Html, sel: &str, attr: &str) -> Option<String> {
    let selector = Selector::parse(sel).ok()?;
    doc.select(&selector)
        .next()?
        .value()
        .attr(attr)
        .map(str::to_string)
}

fn link_href(doc: &Html, base_url: &reqwest::Url, sel: &str) -> Option<String> {
    let selector = Selector::parse(sel).ok()?;
    let href = doc.select(&selector).next()?.value().attr("href")?;
    base_url.join(href).ok().map(|u| u.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base() -> reqwest::Url {
        reqwest::Url::parse("https://example.com/page").unwrap()
    }

    fn run(html: &str) -> Extracted {
        extract_page(html, &base(), &ExtractOptions::default())
    }

    /// 契约的核心断言：title 来自 OG、description 回落到 meta[name]，
    /// 两者出处不同且都被如实记录 —— 旧实现会把它们压扁成一个 source="og"。
    #[test]
    fn per_field_sources_capture_mixed_origins() {
        let e = run(r#"<html><head>
                <meta property="og:title" content="OG Title"/>
                <meta name="description" content="Plain description"/>
               </head></html>"#);
        assert_eq!(e.meta.title.as_deref(), Some("OG Title"));
        assert_eq!(e.meta.sources["title"].extractor, MetaExtractor::Og);
        assert_eq!(e.meta.description.as_deref(), Some("Plain description"));
        assert_eq!(
            e.meta.sources["description"].extractor,
            MetaExtractor::MetaName
        );
        assert_eq!(
            e.meta.sources["description"].raw_key.as_deref(),
            Some("description")
        );
    }

    #[test]
    fn title_falls_back_through_the_whole_chain() {
        let only_title = run("<html><head><title>Plain</title></head></html>");
        assert_eq!(only_title.meta.title.as_deref(), Some("Plain"));
        assert_eq!(
            only_title.meta.sources["title"].extractor,
            MetaExtractor::TitleTag
        );

        let tw = run(
            r#"<html><head><meta name="twitter:title" content="TW"/><title>Plain</title></head></html>"#,
        );
        assert_eq!(tw.meta.title.as_deref(), Some("TW"));
        assert_eq!(
            tw.meta.sources["title"].extractor,
            MetaExtractor::TwitterCard
        );
    }

    #[test]
    fn blank_values_are_skipped_not_recorded() {
        let e = run(
            r#"<html><head><meta property="og:title" content="   "/><title>Real</title></head></html>"#,
        );
        assert_eq!(e.meta.title.as_deref(), Some("Real"));
        assert_eq!(e.meta.sources["title"].extractor, MetaExtractor::TitleTag);
    }

    /// 每一张声明都独立成条，不做择优
    #[test]
    fn every_declared_icon_is_returned() {
        let e = run(r#"<html><head>
                <link rel="icon" sizes="16x16" href="/i16.png"/>
                <link rel="icon" sizes="32x32" href="/i32.png"/>
                <link rel="apple-touch-icon" sizes="180x180" href="/touch.png"/>
                <link rel="mask-icon" href="/mask.svg"/>
               </head></html>"#);
        let kinds: Vec<_> = e.assets.iter().map(|a| a.extractor).collect();
        assert_eq!(
            kinds
                .iter()
                .filter(|k| **k == AssetExtractor::LinkIcon)
                .count(),
            2
        );
        assert!(kinds.contains(&AssetExtractor::AppleTouchIcon));
        assert!(kinds.contains(&AssetExtractor::LinkMaskIcon));
        // 声明属性原样保留
        let touch = e
            .assets
            .iter()
            .find(|a| a.extractor == AssetExtractor::AppleTouchIcon)
            .unwrap();
        assert_eq!(touch.declared.sizes.as_deref(), Some("180x180"));
        assert_eq!(touch.resolved_url, "https://example.com/touch.png");
        // svg 后缀识别为矢量
        let mask = e
            .assets
            .iter()
            .find(|a| a.extractor == AssetExtractor::LinkMaskIcon)
            .unwrap();
        assert_eq!(mask.is_vector, Some(true));
    }

    /// 只有在页面一个 icon 都没声明时才走约定式兜底
    #[test]
    fn favicon_ico_fallback_only_when_nothing_declared() {
        let bare = run("<html><head></head></html>");
        assert!(bare
            .assets
            .iter()
            .any(|a| a.extractor == AssetExtractor::FaviconIcoFallback));

        let declared = run(r#"<html><head><link rel="icon" href="/i.png"/></head></html>"#);
        assert!(!declared
            .assets
            .iter()
            .any(|a| a.extractor == AssetExtractor::FaviconIcoFallback));
    }

    /// rel="shortcut icon" 这种多值 rel 要能命中，且原值保留
    #[test]
    fn multi_word_rel_is_matched_and_preserved() {
        let e = run(r#"<html><head><link rel="shortcut icon" href="/f.ico"/></head></html>"#);
        let icon = e
            .assets
            .iter()
            .find(|a| a.extractor == AssetExtractor::LinkIcon)
            .unwrap();
        assert_eq!(icon.declared.rel.as_deref(), Some("shortcut icon"));
        assert!(!e
            .assets
            .iter()
            .any(|a| a.extractor == AssetExtractor::FaviconIcoFallback));
    }

    /// apple-touch-icon 不应被 rel~="icon" 误匹配成 LINK_ICON
    #[test]
    fn apple_touch_icon_is_not_also_a_link_icon() {
        let e = run(r#"<html><head><link rel="apple-touch-icon" href="/t.png"/></head></html>"#);
        assert!(!e
            .assets
            .iter()
            .any(|a| a.extractor == AssetExtractor::LinkIcon));
        assert_eq!(
            e.assets
                .iter()
                .filter(|a| a.extractor == AssetExtractor::AppleTouchIcon)
                .count(),
            1
        );
        // 没有 rel=icon，兜底仍应触发
        assert!(e
            .assets
            .iter()
            .any(|a| a.extractor == AssetExtractor::FaviconIcoFallback));
    }

    #[test]
    fn json_ld_logo_and_image_are_separate_extractors() {
        let e = run(r#"<html><head><script type="application/ld+json">
               {"@type":"Organization","name":"Acme",
                "logo":{"url":"https://cdn.example.com/logo.png"},
                "image":"https://cdn.example.com/hero.jpg"}
               </script></head></html>"#);
        let logo = e
            .assets
            .iter()
            .find(|a| a.extractor == AssetExtractor::JsonLdOrgLogo)
            .unwrap();
        assert_eq!(logo.resolved_url, "https://cdn.example.com/logo.png");
        let image = e
            .assets
            .iter()
            .find(|a| a.extractor == AssetExtractor::JsonLdImage)
            .unwrap();
        assert_eq!(image.resolved_url, "https://cdn.example.com/hero.jpg");
        assert_eq!(e.meta.title.as_deref(), Some("Acme"));
    }

    #[test]
    fn json_ld_graph_is_flattened() {
        let e = run(r#"<html><head><script type="application/ld+json">
               {"@graph":[{"@type":"Organization","logo":"https://cdn.example.com/g.png"}]}
               </script></head></html>"#);
        assert!(e
            .assets
            .iter()
            .any(|a| a.extractor == AssetExtractor::JsonLdOrgLogo
                && a.resolved_url == "https://cdn.example.com/g.png"));
    }

    #[test]
    fn og_and_twitter_blocks_pass_through_with_prefix_stripped() {
        let e = run(r#"<html><head>
                <meta property="og:title" content="T"/>
                <meta property="og:site_name" content="Site"/>
                <meta name="twitter:card" content="summary_large_image"/>
               </head></html>"#);
        assert_eq!(e.opengraph.get("title").map(String::as_str), Some("T"));
        assert_eq!(
            e.opengraph.get("site_name").map(String::as_str),
            Some("Site")
        );
        assert_eq!(
            e.twitter.get("card").map(String::as_str),
            Some("summary_large_image")
        );
        assert_eq!(e.meta.site_name.as_deref(), Some("Site"));
    }

    #[test]
    fn relative_urls_resolve_against_the_final_url() {
        let e = run(r#"<html><head><link rel="icon" href="../icons/a.png"/></head></html>"#);
        let icon = e
            .assets
            .iter()
            .find(|a| a.extractor == AssetExtractor::LinkIcon)
            .unwrap();
        assert_eq!(icon.origin_url, "../icons/a.png", "origin 保留声明原值");
        assert_eq!(icon.resolved_url, "https://example.com/icons/a.png");
    }

    #[test]
    fn data_uri_declarations_are_skipped() {
        let e = run(
            r#"<html><head><link rel="icon" href="data:image/png;base64,iVBOR"/></head></html>"#,
        );
        assert!(!e
            .assets
            .iter()
            .any(|a| a.extractor == AssetExtractor::LinkIcon));
    }

    #[test]
    fn duplicate_declarations_are_deduped_per_extractor() {
        let e = run(r#"<html><head>
                <link rel="icon" href="/same.png"/>
                <link rel="icon" href="/same.png"/>
               </head></html>"#);
        assert_eq!(
            e.assets
                .iter()
                .filter(|a| a.extractor == AssetExtractor::LinkIcon)
                .count(),
            1
        );
    }

    #[test]
    fn feeds_and_alternates_split_the_same_rel() {
        let html = r#"<html><head>
            <link rel="alternate" type="application/rss+xml" title="RSS" href="/feed.xml"/>
            <link rel="alternate" hreflang="zh-CN" href="/zh"/>
           </head></html>"#;
        let opts = ExtractOptions {
            feeds: true,
            alternates: true,
            ..Default::default()
        };
        let e = extract_page(html, &base(), &opts);
        assert_eq!(e.feeds.len(), 1);
        assert_eq!(e.feeds[0].url, "https://example.com/feed.xml");
        assert_eq!(e.alternates.len(), 1);
        assert_eq!(e.alternates[0].hreflang.as_deref(), Some("zh-CN"));
    }

    #[test]
    fn anti_crawler_is_detected_from_title_and_body() {
        let t = run("<html><head><title>Just a moment...</title></head></html>");
        assert_eq!(t.anti_crawler.as_ref().map(|a| a.detected), Some(true));
        assert_eq!(
            t.anti_crawler.unwrap().signal.as_deref(),
            Some("title:just a moment")
        );

        let b = run(
            r#"<html><head><title>ok</title></head><body><script src="/cdn-cgi/challenge-platform/x.js"></script></body></html>"#,
        );
        assert_eq!(b.anti_crawler.map(|a| a.detected), Some(true));

        let clean = run("<html><head><title>Normal Page</title></head></html>");
        assert!(clean.anti_crawler.is_none());
    }

    #[test]
    fn extract_switches_actually_skip_work() {
        let html = r#"<html><head>
            <meta property="og:title" content="T"/>
            <link rel="icon" href="/i.png"/>
            <link rel="manifest" href="/m.json"/>
           </head></html>"#;
        let opts = ExtractOptions {
            meta: false,
            assets: false,
            manifest: false,
            jsonld: false,
            opengraph: false,
            twitter: false,
            ..Default::default()
        };
        let e = extract_page(html, &base(), &opts);
        assert!(e.meta.title.is_none());
        assert!(e.assets.is_empty());
        assert!(e.manifest_url.is_none());
        assert!(e.opengraph.is_empty());
    }

    #[test]
    fn manifest_url_is_resolved_but_not_fetched() {
        let e = run(r#"<html><head><link rel="manifest" href="/site.webmanifest"/></head></html>"#);
        assert_eq!(
            e.manifest_url.as_deref(),
            Some("https://example.com/site.webmanifest")
        );
        // 纯提取层不负责抓取，short_name 只能由上层回填
        assert!(e.meta.short_name.is_none());
    }

    #[test]
    fn keywords_split_and_empty_source_is_dropped() {
        let e = run(
            r#"<html><head><meta name="keywords" content=" vue , admin ,, vben "/></head></html>"#,
        );
        assert_eq!(e.meta.keywords, vec!["vue", "admin", "vben"]);
        assert_eq!(
            e.meta.sources["keywords"].extractor,
            MetaExtractor::MetaName
        );

        let none = run("<html><head></head></html>");
        assert!(none.meta.keywords.is_empty());
        assert!(!none.meta.sources.contains_key("keywords"));
    }
}
