//! 网络侧富化层：把 [`extract`](crate::extract) 产出的**声明**补齐成**事实**。
//!
//! 两件事：
//! 1. [`fetch_manifest`] —— 拉取 Web App Manifest。这条路径 scrapper 此前完全没有，
//!    导致 `short_name`（大图模式下方那行短文案的唯一标准来源）和 manifest 声明的
//!    多尺寸 `icons[]` 全部拿不到。
//! 2. [`process_assets`] —— 按 [`AssetDownload`] 取回图片正文，算出真实尺寸 / MIME /
//!    `contentHash`，再按模式决定丢弃、内联还是上传。
//!
//! 图片尺寸是**手工读文件头**得出的（见 [`image_dimensions`]）：只需前几十字节，不解码
//! 像素，也就不必为此引入一整套图像编解码依赖 —— 与本 crate 一贯的精简依赖取向一致。

use crate::contract::{
    Asset, AssetDownload, AssetExtractor, AssetOptions, DeclaredAttrs, ManifestBlock,
};
use crate::oss::OssClient;
use crate::scraper;
use sha2::{Digest, Sha256};

/// 单张图片正文的默认上限，兜底用；实际以 [`AssetOptions::max_bytes`] 为准。
const HARD_MAX_ASSET_BYTES: u64 = 16 * 1024 * 1024;

/// Manifest 里可以回填到 [`PageMeta`](crate::contract::PageMeta) 的字段。
#[derive(Debug, Default)]
pub struct ManifestMeta {
    pub name: Option<String>,
    pub short_name: Option<String>,
    pub theme_color: Option<String>,
}

/// 拉取并解析 Web App Manifest。
///
/// 返回原样透传的 [`ManifestBlock`]、由 `icons[]` 展开出的资产声明，以及可回填的元数据。
/// 任何一步失败都只产生一条 warning —— manifest 是增量信息，不该让整次抓取失败。
pub async fn fetch_manifest(
    manifest_url: &str,
    base_url: &reqwest::Url,
    client: &reqwest::Client,
) -> Result<(ManifestBlock, Vec<Asset>, ManifestMeta), String> {
    let parsed = reqwest::Url::parse(manifest_url).map_err(|e| format!("bad manifest url: {e}"))?;
    scraper::validate_url_scheme(&parsed)
        .map_err(|_| "manifest url scheme rejected".to_string())?;
    scraper::validate_target_host(&parsed)
        .await
        .map_err(|e| format!("manifest host rejected: {e:?}"))?;

    let response = client
        .get(parsed.clone())
        .send()
        .await
        .map_err(|e| format!("manifest fetch failed: {e}"))?
        .error_for_status()
        .map_err(|e| format!("manifest fetch failed: {e}"))?;

    let bytes = scraper::read_body_capped(response, scraper::MAX_FAVICON_BYTES)
        .await
        .map_err(|e| format!("manifest read failed: {e}"))?;

    let raw: serde_json::Value =
        serde_json::from_slice(&bytes).map_err(|e| format!("manifest parse failed: {e}"))?;

    let meta = ManifestMeta {
        name: json_string(&raw, "name"),
        short_name: json_string(&raw, "short_name").or_else(|| json_string(&raw, "shortName")),
        theme_color: json_string(&raw, "theme_color").or_else(|| json_string(&raw, "themeColor")),
    };

    // manifest 里的相对路径以 manifest 文件自身为基准解析，不是以页面 URL
    let icon_base = parsed.clone();
    let mut assets = Vec::new();
    if let Some(icons) = raw.get("icons").and_then(|v| v.as_array()) {
        for icon in icons {
            let Some(src) = icon.get("src").and_then(|v| v.as_str()) else {
                continue;
            };
            let src = src.trim();
            if src.is_empty() || src.starts_with("data:") {
                continue;
            }
            let Ok(resolved) = icon_base.join(src) else {
                continue;
            };
            let resolved_url = resolved.to_string();
            if assets
                .iter()
                .any(|a: &Asset| a.resolved_url == resolved_url)
            {
                continue;
            }
            let declared = DeclaredAttrs {
                rel: None,
                sizes: icon
                    .get("sizes")
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
                type_: icon
                    .get("type")
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
                media: None,
                purpose: icon
                    .get("purpose")
                    .and_then(|v| v.as_str())
                    .map(str::to_string),
            };
            let is_vector = Some(
                declared.type_.as_deref().is_some_and(|t| t.contains("svg"))
                    || resolved.path().to_ascii_lowercase().ends_with(".svg"),
            );
            assets.push(Asset {
                extractor: AssetExtractor::ManifestIcon,
                declared,
                origin_url: src.to_string(),
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
    }

    let _ = base_url; // manifest icons 以 manifest 自身为基准，页面 URL 仅供将来诊断用
    Ok((
        ManifestBlock {
            url: parsed.to_string(),
            raw,
        },
        assets,
        meta,
    ))
}

fn json_string(v: &serde_json::Value, key: &str) -> Option<String> {
    v.get(key)
        .and_then(|x| x.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

/// 按 [`AssetOptions`] 取回并富化图片资产。
///
/// 并发处理，单张失败只写进该张自己的 `error`，不影响其余图片，也不影响整次抓取。
/// 超出 [`AssetOptions::max_count`] 的部分仍会保留在结果里，但不发请求 —— 契约承诺
/// "超出部分仍会出现在 assets[] 里但不下载"。
pub async fn process_assets(
    assets: Vec<Asset>,
    opts: &AssetOptions,
    client: &reqwest::Client,
    oss: Option<&OssClient>,
) -> (Vec<Asset>, Vec<String>) {
    if opts.download == AssetDownload::None || assets.is_empty() {
        return (assets, Vec::new());
    }

    // OSS 未配置时 UPLOAD 自动降级为 PROBE，契约里写明了这个行为
    let mode = match (opts.download, oss.is_some()) {
        (AssetDownload::Upload, false) => AssetDownload::Probe,
        (m, _) => m,
    };
    let cap = opts.max_bytes.min(HARD_MAX_ASSET_BYTES) as usize;

    let futures = assets
        .into_iter()
        .enumerate()
        .map(|(idx, asset)| async move {
            if idx >= opts.max_count {
                return asset;
            }
            match enrich_one(&asset, mode, cap, client, oss).await {
                Ok(enriched) => enriched,
                Err(e) => Asset {
                    error: Some(e),
                    ..asset
                },
            }
        });

    let processed: Vec<Asset> = futures_util::future::join_all(futures).await;

    let warnings = processed
        .iter()
        .filter_map(|a| {
            a.error
                .as_ref()
                .map(|e| format!("asset {} ({:?}): {e}", a.resolved_url, a.extractor))
        })
        .collect();

    (processed, warnings)
}

async fn enrich_one(
    asset: &Asset,
    mode: AssetDownload,
    cap: usize,
    client: &reqwest::Client,
    oss: Option<&OssClient>,
) -> Result<Asset, String> {
    let parsed = reqwest::Url::parse(&asset.resolved_url).map_err(|e| format!("bad url: {e}"))?;
    scraper::validate_url_scheme(&parsed).map_err(|_| "scheme rejected".to_string())?;
    scraper::validate_target_host(&parsed)
        .await
        .map_err(|e| format!("host rejected: {e:?}"))?;

    // 部分 CDN 对无 Referer 的图片请求返回 403，带上同源 Referer 提高成功率
    let referer = format!("{}://{}", parsed.scheme(), parsed.host_str().unwrap_or(""));
    let response = client
        .get(parsed.clone())
        .header(reqwest::header::REFERER, &referer)
        .send()
        .await
        .map_err(|e| format!("fetch failed: {e}"))?
        .error_for_status()
        .map_err(|e| format!("fetch failed: {e}"))?;

    let header_mime = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        // 去掉控制字符，防止拼进 data: URI 时被注入
        .map(|s| {
            s.chars()
                .take_while(|c| !c.is_ascii_control())
                .collect::<String>()
        })
        .map(|s| s.split(';').next().unwrap_or("").trim().to_string())
        .filter(|s| !s.is_empty());

    let bytes = scraper::read_body_capped(response, cap)
        .await
        .map_err(|e| format!("read failed: {e}"))?;

    if bytes.is_empty() {
        return Err("empty body".to_string());
    }

    // 文件魔数优先于响应头：不少站点把 .ico/.svg 一律标成 text/plain 或 octet-stream
    let mime = sniff_mime(&bytes)
        .map(str::to_string)
        .or(header_mime)
        .unwrap_or_else(|| "application/octet-stream".to_string());

    let dims = image_dimensions(&bytes);
    let content_hash = format!("sha256:{}", hex::encode(Sha256::digest(&bytes)));
    let is_vector = mime.contains("svg") || asset.is_vector == Some(true);

    let mut out = Asset {
        width: dims.map(|(w, _)| w),
        height: dims.map(|(_, h)| h),
        byte_size: Some(bytes.len() as u64),
        mime: Some(mime.clone()),
        is_vector: Some(is_vector),
        content_hash: Some(content_hash.clone()),
        error: None,
        ..asset.clone()
    };

    match mode {
        AssetDownload::None | AssetDownload::Probe => {} // 正文用完即丢
        AssetDownload::Inline => {
            use base64::{engine::general_purpose::STANDARD, Engine};
            out.data_url = Some(format!("data:{mime};base64,{}", STANDARD.encode(&bytes)));
        }
        AssetDownload::Upload => {
            let oss = oss.ok_or_else(|| "oss not configured".to_string())?;
            // key 取自**字节**而不是源 URL：同一张图挂在多个 URL 下只存一份，且 key 一旦写入
            // 内容永不改变（源站换图会得到新 key，而不是覆盖旧 key 的内容）
            let key = oss.asset_key(&content_hash, "asset");
            out.storage_key = Some(
                oss.upload_bytes(&key, &bytes, &mime)
                    .await
                    .map_err(|e| format!("oss upload failed: {e:?}"))?,
            );
        }
    }

    Ok(out)
}

// ─────────────────────────────────────────────────────────────────────────────
// 图片文件头解析
// ─────────────────────────────────────────────────────────────────────────────

/// 按魔数识别 MIME。只覆盖网页图标/社交图实际会出现的格式。
pub fn sniff_mime(b: &[u8]) -> Option<&'static str> {
    if b.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
        return Some("image/png");
    }
    if b.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some("image/jpeg");
    }
    if b.starts_with(b"GIF87a") || b.starts_with(b"GIF89a") {
        return Some("image/gif");
    }
    if b.len() >= 12 && b.starts_with(b"RIFF") && &b[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    if b.starts_with(&[0x00, 0x00, 0x01, 0x00]) {
        return Some("image/x-icon");
    }
    if b.len() >= 4 && b[..4].eq_ignore_ascii_case(b"<svg") {
        return Some("image/svg+xml");
    }
    // 跳过前导空白/XML 声明后再看是不是 SVG
    let head = &b[..b.len().min(512)];
    if let Ok(s) = std::str::from_utf8(head) {
        let t = s.trim_start();
        if t.starts_with("<?xml") && s.contains("<svg") {
            return Some("image/svg+xml");
        }
    }
    None
}

/// 只读文件头得出像素尺寸，不解码像素数据。
///
/// 覆盖 PNG / GIF / JPEG / WebP / ICO。SVG 是矢量图，没有固有像素尺寸，返回 `None`
/// （调用方看 `isVector` 即可，小图场景本就该优先选它）。
pub fn image_dimensions(b: &[u8]) -> Option<(u32, u32)> {
    // PNG: IHDR 紧跟 8 字节签名，宽高是大端 u32
    if b.starts_with(&[0x89, b'P', b'N', b'G']) && b.len() >= 24 {
        return Some((be_u32(b, 16)?, be_u32(b, 20)?));
    }
    // GIF: 逻辑屏幕宽高，小端 u16
    if (b.starts_with(b"GIF87a") || b.starts_with(b"GIF89a")) && b.len() >= 10 {
        return Some((le_u16(b, 6)? as u32, le_u16(b, 8)? as u32));
    }
    // ICO: 目录首项的宽高各占一字节，0 表示 256
    if b.starts_with(&[0x00, 0x00, 0x01, 0x00]) && b.len() >= 8 {
        let w = if b[6] == 0 { 256 } else { b[6] as u32 };
        let h = if b[7] == 0 { 256 } else { b[7] as u32 };
        return Some((w, h));
    }
    if b.len() >= 12 && b.starts_with(b"RIFF") && &b[8..12] == b"WEBP" {
        return webp_dimensions(b);
    }
    if b.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return jpeg_dimensions(b);
    }
    None
}

/// WebP 三种子格式各有各的头部布局。
fn webp_dimensions(b: &[u8]) -> Option<(u32, u32)> {
    let fourcc = b.get(12..16)?;
    match fourcc {
        b"VP8X" => {
            // 24-bit 小端，存的是 width-1 / height-1
            let w = le_u24(b, 24)? + 1;
            let h = le_u24(b, 27)? + 1;
            Some((w, h))
        }
        b"VP8 " => {
            // 关键帧起始码 0x9D 0x01 0x2A 之后是两个 14-bit 小端值
            let start = b.get(23..26)?;
            if start != [0x9D, 0x01, 0x2A] {
                return None;
            }
            let w = (le_u16(b, 26)? & 0x3FFF) as u32;
            let h = (le_u16(b, 28)? & 0x3FFF) as u32;
            Some((w, h))
        }
        b"VP8L" => {
            if *b.get(20)? != 0x2F {
                return None;
            }
            let bits = u32::from_le_bytes(b.get(21..25)?.try_into().ok()?);
            Some(((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1))
        }
        _ => None,
    }
}

/// 扫描 JPEG 段直到 SOFn，宽高在段内偏移 +5/+7，大端 u16。
fn jpeg_dimensions(b: &[u8]) -> Option<(u32, u32)> {
    let mut i = 2usize;
    while i + 9 < b.len() {
        if b[i] != 0xFF {
            i += 1;
            continue;
        }
        let marker = b[i + 1];
        // 填充字节与无长度段直接跳过
        if marker == 0xFF {
            i += 1;
            continue;
        }
        if matches!(marker, 0xD8 | 0x01) || (0xD0..=0xD7).contains(&marker) {
            i += 2;
            continue;
        }
        let seg_len = be_u16(b, i + 2)? as usize;
        // SOF0..SOF15，排除 DHT(C4)/JPG(C8)/DAC(CC) 这三个非 SOF
        let is_sof = (0xC0..=0xCF).contains(&marker) && !matches!(marker, 0xC4 | 0xC8 | 0xCC);
        if is_sof {
            let h = be_u16(b, i + 5)? as u32;
            let w = be_u16(b, i + 7)? as u32;
            return Some((w, h));
        }
        if seg_len < 2 {
            return None;
        }
        i += 2 + seg_len;
    }
    None
}

fn be_u16(b: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_be_bytes(b.get(at..at + 2)?.try_into().ok()?))
}
fn be_u32(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_be_bytes(b.get(at..at + 4)?.try_into().ok()?))
}
fn le_u16(b: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_le_bytes(b.get(at..at + 2)?.try_into().ok()?))
}
fn le_u24(b: &[u8], at: usize) -> Option<u32> {
    let s = b.get(at..at + 3)?;
    Some(u32::from(s[0]) | (u32::from(s[1]) << 8) | (u32::from(s[2]) << 16))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn png_header_yields_dimensions() {
        let mut png = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
        png.extend_from_slice(&[0, 0, 0, 13]); // IHDR 长度
        png.extend_from_slice(b"IHDR");
        png.extend_from_slice(&180u32.to_be_bytes());
        png.extend_from_slice(&120u32.to_be_bytes());
        assert_eq!(image_dimensions(&png), Some((180, 120)));
        assert_eq!(sniff_mime(&png), Some("image/png"));
    }

    #[test]
    fn gif_header_yields_dimensions() {
        let mut gif = b"GIF89a".to_vec();
        gif.extend_from_slice(&64u16.to_le_bytes());
        gif.extend_from_slice(&32u16.to_le_bytes());
        assert_eq!(image_dimensions(&gif), Some((64, 32)));
        assert_eq!(sniff_mime(&gif), Some("image/gif"));
    }

    /// ICO 用一个字节存边长，0 是 256 的特例 —— favicon.ico 常见 256x256
    #[test]
    fn ico_treats_zero_as_256() {
        let ico = vec![0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00];
        assert_eq!(image_dimensions(&ico), Some((256, 256)));
        let small = vec![0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 32, 32];
        assert_eq!(image_dimensions(&small), Some((32, 32)));
        assert_eq!(sniff_mime(&ico), Some("image/x-icon"));
    }

    #[test]
    fn jpeg_scans_segments_to_reach_sof() {
        let mut jpg = vec![0xFF, 0xD8, 0xFF];
        // 先插一个 APP0 段（长度 16），迫使扫描器真的走一次跳段
        jpg.truncate(2);
        jpg.extend_from_slice(&[0xFF, 0xE0, 0x00, 0x10]);
        jpg.extend_from_slice(&[0u8; 14]);
        // 再放 SOF0：长度、精度、高、宽
        jpg.extend_from_slice(&[0xFF, 0xC0, 0x00, 0x11, 0x08]);
        jpg.extend_from_slice(&300u16.to_be_bytes()); // height
        jpg.extend_from_slice(&400u16.to_be_bytes()); // width
        jpg.extend_from_slice(&[0u8; 8]);
        // 补回 SOI 后的魔数前缀以通过 sniff
        let mut full = vec![0xFF, 0xD8, 0xFF];
        full.extend_from_slice(&jpg[2..]);
        assert_eq!(jpeg_dimensions(&full), Some((400, 300)));
        assert_eq!(sniff_mime(&full), Some("image/jpeg"));
    }

    #[test]
    fn webp_vp8x_extended_form() {
        let mut w = b"RIFF".to_vec();
        w.extend_from_slice(&[0, 0, 0, 0]);
        w.extend_from_slice(b"WEBP");
        w.extend_from_slice(b"VP8X");
        w.extend_from_slice(&[0, 0, 0, 0]); // chunk size
        w.extend_from_slice(&[0, 0, 0, 0]); // flags + reserved
                                            // width-1 = 511, height-1 = 255（24-bit 小端）
        w.extend_from_slice(&[0xFF, 0x01, 0x00]);
        w.extend_from_slice(&[0xFF, 0x00, 0x00]);
        assert_eq!(image_dimensions(&w), Some((512, 256)));
        assert_eq!(sniff_mime(&w), Some("image/webp"));
    }

    #[test]
    fn svg_is_sniffed_but_has_no_pixel_dimensions() {
        let svg = br#"<svg xmlns="http://www.w3.org/2000/svg"></svg>"#;
        assert_eq!(sniff_mime(svg), Some("image/svg+xml"));
        assert_eq!(image_dimensions(svg), None);

        let with_decl = br#"<?xml version="1.0"?><svg xmlns="x"></svg>"#;
        assert_eq!(sniff_mime(with_decl), Some("image/svg+xml"));
    }

    #[test]
    fn garbage_yields_neither_mime_nor_dimensions() {
        let junk = b"not an image at all";
        assert_eq!(sniff_mime(junk), None);
        assert_eq!(image_dimensions(junk), None);
    }

    /// 截断的头部不能 panic，只能返回 None
    #[test]
    fn truncated_headers_are_safe() {
        assert_eq!(image_dimensions(&[0x89, b'P', b'N', b'G']), None);
        assert_eq!(image_dimensions(b"GIF89a"), None);
        assert_eq!(image_dimensions(&[0xFF, 0xD8, 0xFF]), None);
        assert_eq!(image_dimensions(b"RIFF____WEBPVP8X"), None);
    }

    #[tokio::test]
    async fn download_none_short_circuits_without_touching_the_network() {
        let assets = vec![Asset {
            extractor: AssetExtractor::LinkIcon,
            declared: DeclaredAttrs::default(),
            origin_url: "/i.png".into(),
            // 若真的发起请求，SSRF 防护之外这个地址也必然失败；断言 error 为空即证明没发
            resolved_url: "https://127.0.0.1:1/i.png".into(),
            width: None,
            height: None,
            byte_size: None,
            mime: None,
            is_vector: Some(false),
            content_hash: None,
            storage_key: None,
            data_url: None,
            error: None,
        }];
        let client = reqwest::Client::new();
        let opts = AssetOptions {
            download: AssetDownload::None,
            ..Default::default()
        };
        let (out, warnings) = process_assets(assets, &opts, &client, None).await;
        assert_eq!(out.len(), 1);
        assert!(out[0].error.is_none());
        assert!(out[0].content_hash.is_none());
        assert!(warnings.is_empty());
    }

    /// 超出 max_count 的部分保留在结果里但不发请求
    #[tokio::test]
    async fn assets_beyond_max_count_are_kept_but_not_fetched() {
        let mk = |u: &str| Asset {
            extractor: AssetExtractor::LinkIcon,
            declared: DeclaredAttrs::default(),
            origin_url: u.into(),
            resolved_url: u.into(),
            width: None,
            height: None,
            byte_size: None,
            mime: None,
            is_vector: None,
            content_hash: None,
            storage_key: None,
            data_url: None,
            error: None,
        };
        let assets = vec![mk("not-a-valid-url-1"), mk("not-a-valid-url-2")];
        let client = reqwest::Client::new();
        let opts = AssetOptions {
            download: AssetDownload::Probe,
            max_count: 1,
            ..Default::default()
        };
        let (out, _) = process_assets(assets, &opts, &client, None).await;
        assert_eq!(out.len(), 2);
        assert!(out[0].error.is_some(), "第 1 张应被处理并因非法 URL 报错");
        assert!(out[1].error.is_none(), "第 2 张超出 max_count，不该被处理");
    }
}
