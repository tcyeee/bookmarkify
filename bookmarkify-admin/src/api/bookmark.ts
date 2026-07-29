import { requestClient } from '#/api/request';

export interface CategoryVO {
  id: string;
  slug: string;
  name: string;
  color?: string;
}

/** 书签抓取结果：PENDING(等待抓取) / SUCCESS(抓取成功) / UNREACHABLE(抓取失败,可能是暂时性故障) */
export type BookmarkParseStatus = 'PENDING' | 'SUCCESS' | 'UNREACHABLE';

export interface SimilarSite {
  name: string;
  domain: string;
  reason: string;
  /** 本地是否已收录（后端按 urlHost 归一化比对回填） */
  exists?: boolean;
}

/** 图片用途：由后端从 scrapper 报告的 extractor 推导而来 */
export type AssetRole = 'FAVICON' | 'LOGO' | 'SCREENSHOT' | 'SOCIAL';
/** 可信度：TRUSTED=来源语义明确；DEGRADED=借用其它用途的图凑数 */
export type AssetQuality = 'DEGRADED' | 'TRUSTED';
/** 展示模式：TILE=大图+短名，LIST=小图+全名 */
export type DisplayMode = 'LIST' | 'TILE';

/** 单张图片资产（site_asset，一行一图） */
export interface SiteAsset {
  id: string;
  /** 用途(后端推导) */
  role: AssetRole;
  /** 出处(scrapper 报告的事实)，如 LINK_ICON / MANIFEST_ICON / OG_IMAGE */
  extractor: string;
  quality: AssetQuality;
  /** 可直接预览的地址(私有桶已签名) */
  url?: string;
  resolvedUrl: string;
  width?: number;
  height?: number;
  byteSize?: number;
  mime?: string;
  isVector: boolean;
  contentHash?: string;
  isPrimary: boolean;
  /** 与本书签其它资产字节相同 —— 说明该站没有独立 LOGO */
  duplicateOfOther: boolean;
  errorMsg?: string;
}

/** 某展示模式下的图标设置（site_display_pref，按 书签×模式 分行） */
export interface SiteDisplayPref {
  displayMode: DisplayMode;
  iconPadding: number;
  iconBgColor?: string;
  /** 人工钉死的资产ID，覆盖自动选择 */
  pinnedAssetId?: string;
  /** 该模式下实际会渲染的地址 */
  previewUrl?: string;
  /** true 表示该模式下会走首字母色块 */
  monogram: boolean;
}

export interface BookmarkEntity {
  id: string;
  urlHost: string;
  urlPath?: string;
  urlScheme: string;
  appName?: string;
  title?: string;
  description?: string;
  // 该书签声明的全部图片资产（一行一图），后台刻意展示全部以便排查
  assets: SiteAsset[];
  // 各展示模式下的图标设置
  displayPrefs: SiteDisplayPref[];
  parseStatus: BookmarkParseStatus;
  isActivity: boolean;
  /** 抓取成功但页面疑似反爬虫/WAF挑战页，内容可能不可靠 */
  antiCrawlerBlocked: boolean;
  parseErrMsg?: string;
  createTime: string;
  updateTime?: string;
  categories?: CategoryVO[];
  /** 疑似涉黄/涉赌等违规内容(NSFW)，由 DeepSeek 判断 */
  nsfw: boolean;
}

export interface BookmarkSearchParams {
  name?: string;
  status?: BookmarkParseStatus;
  currentPage?: number;
  pageSize?: number;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/**
 * 获取全部书签信息（分页）
 */
export async function getBookmarkListApi(params: BookmarkSearchParams) {
  return requestClient.post<PageResult<BookmarkEntity>>('/admin/bookmark/all', params);
}

/**
 * 修改书签编辑设置（内边距、背景色、是否高清、AppName），单一保存端点
 */
export async function updateBookmarkIconApi(
  bookmarkId: string,
  data: {
    appName?: null | string;
    /** 显示设置按展示模式分行：大图的内边距/背景色与列表行互不影响 */
    displayMode: DisplayMode;
    iconBgColor?: null | string;
    iconPadding: number;
    /** 人工钉死用哪张图；为空表示走自动选择 */
    pinnedAssetId?: null | string;
  },
) {
  return requestClient.post<void>(`/admin/bookmark/${bookmarkId}/icon`, data);
}

/** 重新获取预览结果：重新解析得到的网站标题、小图标与高清 LOGO（不落库） */
export interface BookmarkRefetchResult {
  /** 新解析的网站图标地址 */
  iconUrl?: string;
  /** scrapper 新解析的高清 LOGO 地址，未抓到为空 */
  logoUrl?: string;
  title?: string;
}

/**
 * 重新获取：重新解析网站标题与图标（不落库），返回预览数据供对比选择
 */
export async function refetchBookmarkApi(bookmarkId: string) {
  return requestClient.post<BookmarkRefetchResult>(
    `/admin/bookmark/${bookmarkId}/refetch`,
  );
}

/**
 * 应用重新获取的结果：按选择采用新标题 / 新图标并持久化，返回更新后的书签
 */
export async function applyRefetchBookmarkApi(
  bookmarkId: string,
  data: { useNewIcon: boolean; useNewLogo: boolean; useNewTitle: boolean },
) {
  return requestClient.post<BookmarkEntity>(
    `/admin/bookmark/${bookmarkId}/refetch/apply`,
    data,
  );
}

/** 手动覆盖式设置某书签的分类，返回更新后的分类列表 */
export async function updateBookmarkCategoriesApi(
  bookmarkId: string,
  categoryIds: string[],
) {
  return requestClient.post<CategoryVO[]>(
    `/admin/bookmark/${bookmarkId}/categories`,
    { categoryIds },
  );
}

/** 对某书签重新执行 DeepSeek 自动归类，返回更新后的分类列表 */
export async function recategorizeBookmarkApi(bookmarkId: string) {
  return requestClient.post<CategoryVO[]>(
    `/admin/bookmark/${bookmarkId}/categorize`,
  );
}

/** AI 推荐相似网站（仅展示，不入库；返回项带 exists 标记本地是否已收录） */
export async function findSimilarSitesApi(bookmarkId: string) {
  return requestClient.post<SimilarSite[]>(
    `/admin/bookmark/${bookmarkId}/similar`,
  );
}

/** 一键收录：异步收录传入的相似网站域名，立即返回；进度经 WebSocket 推送 */
export async function ingestSimilarSitesApi(
  bookmarkId: string,
  domains: string[],
) {
  return requestClient.post<{ count: number; started: boolean }>(
    `/admin/bookmark/${bookmarkId}/similar/ingest`,
    { domains },
  );
}

/** DeepSeek 生成书签简称建议（不落库） */
export async function generateAppNameApi(bookmarkId: string) {
  return requestClient.post<{ appName?: string }>(
    `/admin/bookmark/${bookmarkId}/appname/generate`,
  );
}

/** 书签检测结果：直接调用 scrapper 重新抓取拿到的全部原始字段，附带检测后落库的活性状态 */
export interface BookmarkLivenessResult {
  success: boolean;
  title?: string;
  description?: string;
  image?: string;
  favicon?: string;
  logo?: string;
  source?: string;
  cached?: boolean;
  screenshot?: string;
  errorMsg?: string;
  isActivity: boolean;
  parseStatus: BookmarkParseStatus;
  antiCrawlerBlocked: boolean;
}

/** 对某个书签进行活性检测：直接调用 scrapper 重新抓取一次，返回其给出的全部字段，并同步落库 isActivity/parseStatus */
export async function checkBookmarkLivenessApi(bookmarkId: string) {
  return requestClient.post<BookmarkLivenessResult>(
    `/admin/bookmark/${bookmarkId}/liveness`,
  );
}

/** 一键更新：重新抓取网站信息并直接覆盖持久化标题/简介/图标/高清 LOGO，返回更新后的书签 */
export async function refreshBookmarkApi(bookmarkId: string) {
  return requestClient.post<BookmarkEntity>(
    `/admin/bookmark/${bookmarkId}/refresh`,
  );
}

/** 手动编辑书签基础信息（标题/简介），返回更新后的书签 */
export async function updateBookmarkBasicInfoApi(
  bookmarkId: string,
  data: { title?: string; description?: string },
) {
  return requestClient.post<BookmarkEntity>(
    `/admin/bookmark/${bookmarkId}/update`,
    data,
  );
}
