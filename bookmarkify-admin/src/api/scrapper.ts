import { requestClient } from '#/api/request';

/**
 * bookmarkify-scrapper `POST /scrape` 的契约（管理后台侧镜像）。
 *
 * 与 `bookmarkify-scrapper/crates/scraper-service/src/contract.rs` 及
 * `bookmarkify-api/.../entity/dto/scrape/ScrapeContract.kt` 一一对应，**三处必须同步**。
 *
 * 核心约定：scrapper 只报告事实（页面声明了什么），不做业务解释（这些事实该当什么用）。
 * 所以响应里只有 `extractor`（出处），没有 role（用途）—— 用途判定归 API 侧的映射表管。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 请求
// ─────────────────────────────────────────────────────────────────────────────

/** 抓取模式 */
export type RenderMode = 'AUTO' | 'HEADLESS' | 'HTTP';
/** 无头模式下的页面就绪判定 */
export type WaitUntil = 'DOM_CONTENT_LOADED' | 'LOAD' | 'NETWORK_IDLE';
/** 配色偏好，用于抓暗色 LOGO */
export type ColorScheme = 'DARK' | 'LIGHT';
/** 图片下载策略 */
export type AssetDownload = 'INLINE' | 'NONE' | 'PROBE' | 'UPLOAD';
/** 截图输出格式 */
export type ImageFormat = 'JPEG' | 'PNG' | 'WEBP';
/** 缓存模式 */
export type CacheMode = 'BYPASS' | 'DEFAULT' | 'ONLY_IF_CACHED';

export interface Viewport {
  width: number;
  height: number;
  dpr?: number;
}

export interface RenderOptions {
  mode?: RenderMode;
  timeoutMs?: null | number;
  waitUntil?: WaitUntil;
  viewport?: null | Viewport;
  userAgent?: null | string;
  locale?: null | string;
  colorScheme?: ColorScheme | null;
}

export interface ExtractOptions {
  meta?: boolean;
  assets?: boolean;
  manifest?: boolean;
  jsonld?: boolean;
  opengraph?: boolean;
  twitter?: boolean;
  feeds?: boolean;
  alternates?: boolean;
  text?: boolean;
}

export interface AssetOptions {
  download?: AssetDownload;
  maxBytes?: number;
  maxCount?: number;
}

export interface ScreenshotOptions {
  enabled?: boolean;
  fullPage?: boolean;
  format?: ImageFormat;
  quality?: number;
}

export interface CacheOptions {
  mode?: CacheMode;
  maxAgeS?: null | number;
}

export interface RobotsOptions {
  respect?: boolean;
}

/**
 * 完整请求体。
 *
 * scrapper 侧对请求启用了 `deny_unknown_fields`：**多发一个字段会被整体拒绝(422)**，
 * 这是刻意的，避免字段名拼错后被静默忽略成默认值。
 */
export interface ScrapeRequest {
  url: string;
  render?: RenderOptions;
  extract?: ExtractOptions;
  assets?: AssetOptions;
  screenshot?: ScreenshotOptions;
  cache?: CacheOptions;
  robots?: RobotsOptions;
}

// ─────────────────────────────────────────────────────────────────────────────
// 响应
// ─────────────────────────────────────────────────────────────────────────────

/** 实际使用的抓取层 */
export type RenderLayer = 'HEADLESS' | 'HTTP' | 'UNKNOWN';

/** 元数据来源类别 */
export type MetaExtractor =
  | 'HTML_ATTR'
  | 'JSON_LD'
  | 'LINK_TAG'
  | 'MANIFEST'
  | 'META_NAME'
  | 'OG'
  | 'TITLE_TAG'
  | 'TWITTER_CARD'
  | 'UNKNOWN';

/** 图片资源的提取来源 —— 只描述出处，不描述用途 */
export type AssetExtractor =
  | 'APPLE_TOUCH_ICON'
  | 'FAVICON_ICO_FALLBACK'
  | 'JSON_LD_IMAGE'
  | 'JSON_LD_ORG_LOGO'
  | 'LINK_ICON'
  | 'LINK_MASK_ICON'
  | 'MANIFEST_ICON'
  | 'MS_TILE_IMAGE'
  | 'OG_IMAGE'
  | 'TWITTER_IMAGE'
  | 'UNKNOWN';

export interface Redirect {
  url: string;
  status: number;
}

export interface TlsInfo {
  valid: boolean;
  issuer?: string;
  notAfter?: string;
}

export interface Timing {
  dns?: number;
  connect?: number;
  tls?: number;
  ttfb?: number;
  total: number;
}

export interface FetchInfo {
  finalUrl: string;
  redirectChain?: Redirect[];
  httpStatus: number;
  layerUsed: RenderLayer;
  fromCache: boolean;
  contentType?: string;
  charset?: string;
  byteSize?: number;
  tls?: TlsInfo;
  timingMs: Timing;
}

/** 单个元数据字段的出处 */
export interface MetaSource {
  extractor: MetaExtractor;
  rawKey?: string;
}

export interface PageMeta {
  title?: string;
  description?: string;
  siteName?: string;
  shortName?: string;
  canonicalUrl?: string;
  lang?: string;
  themeColor?: string;
  author?: string;
  publishedAt?: string;
  robots?: string;
  keywords?: string[];
  /** 字段级出处：键是本对象的字段名 */
  sources?: Record<string, MetaSource>;
}

/** 源站原样声明的图片属性 */
export interface DeclaredAttrs {
  rel?: string;
  sizes?: string;
  type?: string;
  media?: string;
  purpose?: string;
}

export interface ScrapeAsset {
  extractor: AssetExtractor;
  declared?: DeclaredAttrs;
  originUrl: string;
  resolvedUrl: string;
  width?: number;
  height?: number;
  byteSize?: number;
  mime?: string;
  isVector?: boolean;
  /** 图片字节 sha256，跨 extractor 去重用 */
  contentHash?: string;
  /**
   * 上传对象存储后的 **object key**（不含域名），download = UPLOAD 时才有。
   *
   * 桶是私有读的，这个值**不能直接当图片地址用** —— 签名与缩放是 API 侧的策略，
   * 后台要渲染请用 `ScrapeDebugResult.previews.assets[i]`。
   */
  storageKey?: string;
  dataUrl?: string;
  error?: string;
}

export interface ManifestBlock {
  url: string;
  raw?: unknown;
}

export interface Feed {
  url: string;
  title?: string;
  mime?: string;
}

export interface Alternate {
  url: string;
  hreflang?: string;
  media?: string;
}

export interface Screenshot {
  /** object key，同样需要签名后才能渲染，见 `ScrapeDebugResult.previews.screenshot` */
  storageKey?: string;
  dataUrl?: string;
  width: number;
  height: number;
  format: ImageFormat;
  byteSize?: number;
}

export interface AntiCrawler {
  detected: boolean;
  signal?: string;
}

export interface RobotsVerdict {
  allowed: boolean;
  matchedRule?: string;
}

export interface Diagnostics {
  warnings?: string[];
  antiCrawler?: AntiCrawler;
  robots?: RobotsVerdict;
}

/** 完整响应体。空值与空集合一律省略，因此大量字段是可选的 */
export interface ScrapeResponse {
  /** 回显实际生效的参数（含服务端兜底后的值） */
  request?: unknown;
  fetch: FetchInfo;
  meta?: PageMeta;
  assets?: ScrapeAsset[];
  manifest?: ManifestBlock;
  jsonld?: unknown[];
  opengraph?: Record<string, string>;
  twitter?: Record<string, string>;
  feeds?: Feed[];
  alternates?: Alternate[];
  text?: string;
  screenshot?: Screenshot;
  diagnostics?: Diagnostics;
}

/**
 * 后台预览用的可直接访问地址，由 API 侧对 `storageKey` 签名得到。
 *
 * 单独一层而不是改写 `response`：scrapper 只报告字节落在哪个 object key，域名 / 签名 / 缩放
 * 都是 API 的部署策略。混进 response 就意味着调试页展示的「原始 JSON」不再是 scrapper 真正
 * 返回的东西，而那正是这个页面存在的理由。
 */
export interface ScrapePreviews {
  /** 与 `response.assets` **下标一一对应**；该项未落对象存储时为 null */
  assets: Array<null | string>;
  /** 截图的签名地址；未落对象存储时为 null（此时用 `screenshot.dataUrl`） */
  screenshot: null | string;
}

export interface ScrapeDebugResult {
  /** scrapper 原样返回的响应 */
  response: ScrapeResponse;
  previews: ScrapePreviews;
}

/**
 * 以完整参数调用 scrapper，原样返回其响应，另附后台预览用的签名地址。
 *
 * **无副作用**：不写 site_asset、不改书签、不落快照，纯调试通道。
 * 若要带落库效果，请用 `/admin/website/liveness-check`。
 */
export async function scrapeDebugApi(request: ScrapeRequest) {
  return requestClient.post<ScrapeDebugResult>(
    '/admin/scrapper/scrape',
    request,
  );
}
