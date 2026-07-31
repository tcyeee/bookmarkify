import { requestClient } from '#/api/request';

export interface CategoryVO {
  id: string;
  slug: string;
  name: string;
  color?: string;
}

/**
 * 书签抓取结果：
 * - PENDING     等待抓取
 * - SUCCESS     抓取成功
 * - UNREACHABLE 抓取失败，可能是暂时性故障，仍在按退避曲线重试
 * - ARCHIVED    连续失败达到阈值，已停止巡检（管理员手动刷新/检测可让它回到上面两态）
 */
export type BookmarkParseStatus = 'ARCHIVED' | 'PENDING' | 'SUCCESS' | 'UNREACHABLE';

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
  /** 人工认证：信息已核对 */
  verifyFlag?: boolean;

  /* 巡检调度状态：后台需要能回答「这条为什么还没被复查」「为什么一直没变」 */
  /** 上次成功抓到内容的时间 */
  lastParseAt?: string;
  /** 上次活性探测时间(不论结论) */
  lastCheckAt?: string;
  /** 下次巡检时间(调度游标) */
  nextCheckAt?: string;
  /** 连续探测失败次数，驱动指数退避与归档 */
  consecutiveFail?: number;
  /** 被人工锁定、不会被自动抓取覆盖的字段 */
  lockedFields?: BookmarkLockedField[];
}

/** 管理员手工改过、自动抓取不允许覆盖的字段 */
export type BookmarkLockedField = 'APP_NAME' | 'DESCRIPTION' | 'TITLE';

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

/** 「图片资产 · 重新抓取」的结果 */
export interface BookmarkAssetRefetchResult {
  /** 本次抓取是否成功 */
  success: boolean;
  /**
   * 本次抓取到的图片张数。
   *
   * 为 0 时后端会**保留**库里原有的图片而不是清空，所以前后资产数量可能一样 ——
   * 光比数量分不清「没变化」和「抓崩了但保住了旧图」，靠这个字段区分。
   */
  scrapedAssetCount: number;
  errorMsg?: string;
  /** 落库后的最新书签详情 */
  bookmark: BookmarkEntity;
}

/**
 * 图片资产重新抓取：只重抓图片，不覆盖标题/简介、不解锁人工锁。
 *
 * 与「一键更新」({@link refreshBookmarkApi}) 的区别就在这里 —— 那个会把手工改过的标题
 * 一并改回抓取值。只想补一张缺失的 LOGO 时用这个。
 */
export async function refetchBookmarkAssetsApi(bookmarkId: string) {
  return requestClient.post<BookmarkAssetRefetchResult>(
    `/admin/bookmark/${bookmarkId}/assets/refetch`,
  );
}

/** 一次拉取同域名书签的上限：后台单域名收录几百个页面已属极端，够用且不至于拖慢弹窗 */
const HOST_LOOKUP_PAGE_SIZE = 200;

/**
 * 按域名取出该域名下已收录的全部书签。
 *
 * 后端的 `/admin/bookmark/all` 只有一个模糊 `name`（同时 like appName/title/description/urlHost），
 * 所以这里必须再按 urlHost 精确过滤一次，否则标题里恰好含该域名的其它站点也会混进来。
 */
async function listByHost(urlHost: string): Promise<BookmarkEntity[]> {
  if (!urlHost) return [];
  const res = await getBookmarkListApi({
    name: urlHost,
    currentPage: 1,
    pageSize: HOST_LOOKUP_PAGE_SIZE,
  });
  return res.records.filter((r) => r.urlHost === urlHost);
}

/**
 * 用一个原始 URL 反查已收录的书签。
 *
 * scrapper 调用日志表存的是「抓过哪个地址」，没有书签 ID —— 那张表记录的抓取有可能压根
 * 没落成书签。所以这里按 域名 + 路径 去找：路径命中优先，否则退回该域名的首页那条，
 * 都没有就返回 null（调用方据此提示「尚未收录」，而不是弹一个空壳详情）。
 */
export async function findBookmarkByUrlApi(
  url: string,
): Promise<BookmarkEntity | null> {
  let host = '';
  let path = '/';
  try {
    const parsed = new URL(url);
    host = parsed.host;
    path = parsed.pathname || '/';
  } catch {
    return null;
  }
  const candidates = await listByHost(host);
  if (candidates.length === 0) return null;
  return (
    candidates.find((r) => (r.urlPath || '/') === path) ??
    candidates.find((r) => (r.urlPath || '/') === '/') ??
    candidates[0] ??
    null
  );
}

/** 同域名下已收录的其它页面（排除自身），用于详情弹窗的「关联网站」 */
export async function getSiblingBookmarksApi(
  urlHost: string,
  excludeId: string,
): Promise<BookmarkEntity[]> {
  const list = await listByHost(urlHost);
  return list.filter((r) => r.id !== excludeId);
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
