//! 站点官方 API 适配器 —— 常规抓取被反爬拦下之后的救援路径。
//!
//! # 为什么这里可以按站点特判
//!
//! 本服务其余部分严格站点无关：`extract.rs` 只认 OGP/JSON-LD/manifest 那套通用声明，
//! 全代码此前没有一处 host 特判。这里破例，是被生产环境的事实逼出来的：
//!
//! ```text
//! 生产机（腾讯云 IDC IP）实测：
//!   https://www.bilibili.com/                              → 200
//!   https://www.bilibili.com/video/BV1krNb6vEFY/           → 412   ← 内容页恒拒
//!   https://api.bilibili.com/x/web-interface/view?bvid=…   → 200   ← 同样的数据
//! ```
//!
//! 根路径下发的 `buvid3`/`b_nut` 拿到了、`x/frontend/finger/spi` 的正牌指纹也拿到了，
//! 内容页照样 412 —— 被拒的是**出口 IP**，不是请求长相。这类拦截换什么请求姿势都没用，
//! 无头浏览器同样过不去（它改的是长相，不是 IP），却要付 ~400MB 内存。而站点自己的
//! 公开接口把同一份数据放在另一个域名上，几 KB 就能拿到。
//!
//! # 边界
//!
//! 破的是「站点无关」，不是「只报事实」那条更要紧的规矩：
//!
//! - 只报**事实**：标题、简介、封面都是站点自己说的，role / 质量判定仍归调用方；
//! - 只做**救援**：仅在常规抓取已被反爬拦下后触发，抓得动的站点永远走不到这里；
//! - 出处**如实**记成 `SITE_API`，绝不冒充 `OG` —— 字段级出处是这套契约的立身之本，
//!   谎报出处比拿不到标题的危害大得多。

use serde::Deserialize;

use crate::contract::{Asset, AssetExtractor, MetaExtractor, MetaSource, PageMeta};
use crate::extract::Extracted;
use crate::scraper::{validate_target_host, DEFAULT_UA};

/// 一次站点 API 救援的产物。
#[derive(Debug, Clone, PartialEq)]
pub struct SiteApiMeta {
    /// 站点标识，用于 warnings 与 `rawKey` 前缀，如 `"bilibili"`。
    pub site: &'static str,
    pub title: Option<String>,
    pub description: Option<String>,
    /// 封面图绝对 URL。走正常的资产流水线（探测/下载/上传 OSS），与 `og:image` 同待遇。
    pub cover_url: Option<String>,
}

impl SiteApiMeta {
    /// 摊平成 [`Extracted`]，好让后续阶段（资产探测、OSS 上传、响应组装）原样复用。
    ///
    /// 只填 `meta` 和 `assets`：没有 HTML 就没有 JSON-LD、manifest、feeds 可言，
    /// 这些字段留空是事实，不是遗漏。
    pub fn into_extracted(self) -> Extracted {
        let mut meta = PageMeta::default();

        if let Some(title) = self.title {
            meta_source_insert(&mut meta, "title", format!("{}:view.title", self.site));
            meta.title = Some(title);
        }
        if let Some(desc) = self.description {
            meta_source_insert(&mut meta, "description", format!("{}:view.desc", self.site));
            meta.description = Some(desc);
        }

        let assets = self
            .cover_url
            .map(|url| Asset {
                extractor: AssetExtractor::SiteApiCover,
                declared: Default::default(),
                origin_url: url.clone(),
                resolved_url: url,
                width: None,
                height: None,
                byte_size: None,
                mime: None,
                is_vector: None,
                content_hash: None,
                storage_key: None,
                data_url: None,
                error: None,
            })
            .into_iter()
            .collect();

        Extracted {
            meta,
            assets,
            ..Default::default()
        }
    }
}

/// `meta.sources` 的写入点。单拎出来是为了绕开闭包同时可变借用 `meta` 两处的限制。
fn meta_source_insert(meta: &mut PageMeta, field: &str, raw_key: String) {
    meta.sources.insert(
        field.to_string(),
        MetaSource {
            extractor: MetaExtractor::SiteApi,
            raw_key: Some(raw_key),
        },
    );
}

/// 有没有针对这个 URL 的站点 API 兜底；有就问一次。
///
/// 任何失败都返回 `None` —— 救援是尽力而为，不该制造新的失败原因；调用方手里
/// 已经有一个更该报出去的错误（页面那次被拒的完整现场）。
pub async fn rescue(url: &reqwest::Url, client: &reqwest::Client) -> Option<SiteApiMeta> {
    let host = url.host_str()?.to_ascii_lowercase();
    if is_bilibili(&host) {
        return bilibili_view(url, client).await;
    }
    None
}

/// 认 `bilibili.com` 及其子域（`www.` / `m.`），不认 `xbilibili.com` 这种后缀碰瓷。
fn is_bilibili(host: &str) -> bool {
    host == "bilibili.com" || host.ends_with(".bilibili.com")
}

/// 从 `/video/BVxxx` 或 `/video/av123` 里取出 view 接口的查询串。
///
/// 纯函数，离线可测 —— 网络那半段没法在单测里跑，这半段必须能。
fn bilibili_view_query(url: &reqwest::Url) -> Option<String> {
    let segments: Vec<&str> = url.path_segments()?.filter(|s| !s.is_empty()).collect();
    let idx = segments
        .iter()
        .position(|s| s.eq_ignore_ascii_case("video"))?;
    let id = segments.get(idx + 1)?;

    // 字符集必须校验：path_segments() 返回的段里 `&` `=` 是原样保留的，直接插进查询串
    // 等于让 `/video/BV1&foo=bar` 往 api.bilibili.com 的请求里塞任意参数。
    // BV 号本身是 `BV` + base58，全是 ASCII 字母数字，卡死这一条即可（av 分支早就这么做了）。
    if id.starts_with("BV") && id.len() > 2 && id.chars().all(|c| c.is_ascii_alphanumeric()) {
        return Some(format!("bvid={id}"));
    }
    // av 号是历史形式，站内仍在跳转使用
    if let Some(aid) = id.strip_prefix("av").or_else(|| id.strip_prefix("AV")) {
        if !aid.is_empty() && aid.chars().all(|c| c.is_ascii_digit()) {
            return Some(format!("aid={aid}"));
        }
    }
    None
}

/// B 站视频信息接口的响应。只声明用得上的字段，其余交给 serde 忽略。
#[derive(Deserialize)]
struct BiliEnvelope {
    code: i64,
    data: Option<BiliView>,
}

#[derive(Deserialize)]
struct BiliView {
    title: Option<String>,
    desc: Option<String>,
    /// 封面图。接口给的是 `http://` 开头，见 [`upgrade_to_https`]。
    pic: Option<String>,
}

async fn bilibili_view(url: &reqwest::Url, client: &reqwest::Client) -> Option<SiteApiMeta> {
    let query = bilibili_view_query(url)?;
    let api = format!("https://api.bilibili.com/x/web-interface/view?{query}");
    let parsed = reqwest::Url::parse(&api).ok()?;

    // 接口域名是本文件里的常量、不来自用户输入，但仍照 favicon 下载那条路的规矩过一遍
    // SSRF 校验：DNS 被投毒时这是唯一还站得住的一道。
    validate_target_host(&parsed).await.ok()?;

    let resp = client
        .get(parsed)
        .header(reqwest::header::USER_AGENT, DEFAULT_UA)
        .header(reqwest::header::ACCEPT, "application/json, text/plain, */*")
        // 接口对来源不敏感，但带上和页面同源的 Referer 更像正常调用
        .header(reqwest::header::REFERER, "https://www.bilibili.com/")
        .send()
        .await
        .ok()?;

    if !resp.status().is_success() {
        tracing::debug!(status = resp.status().as_u16(), "bilibili view api non-2xx");
        return None;
    }

    let envelope: BiliEnvelope = resp.json().await.ok()?;
    // 业务码非 0 表示稿件不存在/已删除等，此时 data 是空的
    if envelope.code != 0 {
        tracing::debug!(code = envelope.code, "bilibili view api business error");
        return None;
    }
    let data = envelope.data?;

    let meta = SiteApiMeta {
        site: "bilibili",
        title: data.title.filter(|s| !s.trim().is_empty()),
        description: data.desc.filter(|s| !s.trim().is_empty()),
        cover_url: data
            .pic
            .filter(|s| !s.trim().is_empty())
            .map(upgrade_to_https),
    };
    // 一个字段都没有的成功响应等于没救到，别让调用方以为救援成功了
    if meta.title.is_none() && meta.description.is_none() && meta.cover_url.is_none() {
        return None;
    }
    Some(meta)
}

/// 接口返回的封面是 `http://i0.hdslb.com/…`，CDN 本身支持 https。
/// 升级掉是因为后续探测/下载没必要为一张图退回明文。
fn upgrade_to_https(url: String) -> String {
    match url.strip_prefix("http://") {
        Some(rest) => format!("https://{rest}"),
        None => url,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn url(s: &str) -> reqwest::Url {
        reqwest::Url::parse(s).unwrap()
    }

    #[test]
    fn bilibili_host_matching_rejects_suffix_squatting() {
        assert!(is_bilibili("bilibili.com"));
        assert!(is_bilibili("www.bilibili.com"));
        assert!(is_bilibili("m.bilibili.com"));
        assert!(!is_bilibili("evilbilibili.com"), "后缀碰瓷不该被认作 B 站");
        assert!(!is_bilibili("bilibili.com.evil.cn"));
    }

    #[test]
    fn extracts_bv_and_av_ids() {
        assert_eq!(
            bilibili_view_query(&url("https://www.bilibili.com/video/BV1krNb6vEFY")).as_deref(),
            Some("bvid=BV1krNb6vEFY")
        );
        // 尾斜杠是 B 站自己 301 过去的规范形式，必须同样识别
        assert_eq!(
            bilibili_view_query(&url("https://www.bilibili.com/video/BV1krNb6vEFY/")).as_deref(),
            Some("bvid=BV1krNb6vEFY")
        );
        // 带查询参数（分 P、来源追踪）不影响取号
        assert_eq!(
            bilibili_view_query(&url(
                "https://www.bilibili.com/video/BV1krNb6vEFY/?p=2&from=search"
            ))
            .as_deref(),
            Some("bvid=BV1krNb6vEFY")
        );
        assert_eq!(
            bilibili_view_query(&url("https://www.bilibili.com/video/av170001")).as_deref(),
            Some("aid=170001")
        );
    }

    #[test]
    fn non_video_paths_have_no_adapter() {
        assert!(bilibili_view_query(&url("https://www.bilibili.com/")).is_none());
        assert!(bilibili_view_query(&url("https://space.bilibili.com/123")).is_none());
        // `/video` 后面没有号
        assert!(bilibili_view_query(&url("https://www.bilibili.com/video/")).is_none());
        // 形似但不是合法号
        assert!(bilibili_view_query(&url("https://www.bilibili.com/video/BV")).is_none());
        assert!(bilibili_view_query(&url("https://www.bilibili.com/video/avxyz")).is_none());
    }

    #[test]
    fn rejects_query_injection_in_video_id() {
        // path_segments() 不会转义段内的 `&` / `=`，不校验字符集就等于让 URL 往
        // api.bilibili.com 的查询串里追加任意参数
        assert!(bilibili_view_query(&url("https://www.bilibili.com/video/BV1&foo=bar")).is_none());
        assert!(
            bilibili_view_query(&url("https://www.bilibili.com/video/BV1%26foo=bar")).is_none()
        );
        assert!(bilibili_view_query(&url("https://www.bilibili.com/video/BV1-2")).is_none());
    }

    #[test]
    fn into_extracted_reports_site_api_provenance() {
        let meta = SiteApiMeta {
            site: "bilibili",
            title: Some("标题".into()),
            description: Some("简介".into()),
            cover_url: Some("https://i0.hdslb.com/x.jpg".into()),
        };
        let e = meta.into_extracted();

        assert_eq!(e.meta.title.as_deref(), Some("标题"));
        // 出处必须是 SITE_API —— 冒充 OG 会让下游以为页面真的声明了这些标签
        let src = e.meta.sources.get("title").expect("title 应有出处");
        assert_eq!(src.extractor, MetaExtractor::SiteApi);
        assert_eq!(src.raw_key.as_deref(), Some("bilibili:view.title"));

        assert_eq!(e.assets.len(), 1);
        assert_eq!(e.assets[0].extractor, AssetExtractor::SiteApiCover);
        assert_eq!(e.assets[0].resolved_url, "https://i0.hdslb.com/x.jpg");
        // 没有 HTML 就没有这些块，留空是事实而非遗漏
        assert!(e.jsonld.is_empty() && e.feeds.is_empty() && e.manifest_url.is_none());
    }

    #[test]
    fn cover_url_is_upgraded_to_https() {
        assert_eq!(
            upgrade_to_https("http://i0.hdslb.com/a.jpg".into()),
            "https://i0.hdslb.com/a.jpg"
        );
        assert_eq!(
            upgrade_to_https("https://i0.hdslb.com/a.jpg".into()),
            "https://i0.hdslb.com/a.jpg"
        );
    }

    /// 真打一次 B 站接口。**这是本次改动的存在理由**：生产机上页面 412 而它 200。
    #[tokio::test]
    #[ignore]
    async fn bilibili_view_api_returns_a_title() {
        let client = reqwest::Client::new();
        let got = bilibili_view(
            &url("https://www.bilibili.com/video/BV1krNb6vEFY/"),
            &client,
        )
        .await;
        let meta = got.expect("view 接口应当返回稿件信息");
        assert!(meta.title.is_some_and(|t| !t.is_empty()));
        assert!(meta.cover_url.is_some_and(|c| c.starts_with("https://")));
    }
}
