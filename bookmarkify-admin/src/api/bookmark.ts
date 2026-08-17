// 与 site.ts 互相引用，但两边都是 `import type`：类型导入在编译期就被抹掉，不构成运行时循环
import type { SiteLinkType } from '#/api/site';
import type { UserAdminVO } from '#/api/user-manage';

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
import type { BookmarkParseStatus } from '#/api/enums.generated';

export type { BookmarkParseStatus };

export interface SimilarSite {
  name: string;
  domain: string;
  reason: string;
  /** 本地是否已收录（后端按 urlHost 归一化比对回填） */
  exists?: boolean;
}

/** 图片用途：由后端从 scrapper 报告的 extractor 推导而来 */
import type { AssetRole } from '#/api/enums.generated';

export type { AssetRole };
/** 可信度：TRUSTED=来源语义明确；DEGRADED=借用其它用途的图凑数 */
import type { AssetQuality } from '#/api/enums.generated';

export type { AssetQuality };
/** 展示模式：TILE=大图+短名，LIST=小图+全名 */
import type { DisplayMode } from '#/api/enums.generated';

export type { DisplayMode };

/**
 * 资产归属层级。
 *
 * 图标(favicon/logo)正常是 SITE —— 全站共享一套。只有当某个页面被判成「同域下的另一个
 * 产品」（它声明的图标与站点现有图标字节毫无交集，如 `tools.x.com/tools/a` 与 `/tools/b`）
 * 时，它的图标才会是 PAGE。社交图与截图天然是 PAGE。
 */
import type { AssetOwnerType } from '#/api/enums.generated';

export type { AssetOwnerType };

/** 单张图片资产（site_asset，一行一图） */
export interface SiteAsset {
  id: string;
  /** 用途(后端推导) */
  role: AssetRole;
  /** 挂在站点层还是页面层 */
  ownerType: AssetOwnerType;
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

/**
 * 某展示模式下**规则实际选出**的渲染结果，纯只读（后端 IconRenderVO）。
 *
 * 前身是 `SiteDisplayPref`，混着人工设置（内边距/背景色/钉图）与渲染结果两件事；人工设置那半边
 * 随 `site_display_pref` 表一并移除（2026-08-17），留下的这半边是服务端现算的取图结果。
 */
export interface IconRender {
  displayMode: DisplayMode;
  /** 该模式下实际会渲染的地址 */
  previewUrl?: string;
  /** true 表示该模式下会走首字母色块 */
  monogram: boolean;
}

/**
 * 最近一次抓取留下的页面元数据（`page_meta` 一行一页）。
 *
 * 与 {@link BookmarkEntity.title} / `description` 不是重复：主表那两列是**当前生效值**，
 * 可能已被管理员手工改过并加锁；这里是**抓取原样**，外加主表根本没有的抓取事实
 * （走的哪一层、HTTP 状态码、canonical、语言、主题色、字段级出处）。
 *
 * 整个对象为空表示这一页**从来没有抓成功过** —— 与「抓过但站点没声明描述」是两个结论，
 * 所以后端缺行时给 `undefined` 而不是补一个各字段为空的壳。
 */
export interface PageMetaVO {
  /** 抓取到的页面标题（未经人工覆盖） */
  title?: string;
  /** 抓取到的页面描述（未经人工覆盖） */
  description?: string;
  /** 本页声明的站点名（og:site_name） */
  siteName?: string;
  /** 本页声明的站点短名（manifest.short_name） */
  siteShortName?: string;
  /** 页面自己声明的 canonical 地址 */
  canonicalUrl?: string;
  /** 页面语言（html lang） */
  lang?: string;
  /** 主题色（meta theme-color） */
  themeColor?: string;
  /**
   * 各字段出处的 JSON 原文，形如
   * `{"title":{"extractor":"OG","rawKey":"og:title"}}`。
   */
  metaSources?: string;
  /** 实际抓取层：HTTP=直接取回；HEADLESS=退到了无头浏览器 */
  fetchLayer?: string;
  /** 抓取时目标站返回的 HTTP 状态码 */
  httpStatus?: number;
  /** 疑似反爬挑战页，内容不可靠 */
  antiCrawler: boolean;
  /** 本次抓取时间 */
  fetchedAt?: string;
  /** 该行更新时间 */
  updateTime?: string;
}

export interface BookmarkEntity {
  id: string;
  /**
   * 所属站点ID。
   *
   * 页面层的一半信息其实挂在站点上（品牌名/图标/NSFW/域名活性），这是把两张表接起来的
   * 唯一钥匙 —— 用 urlHost 反查是子串/冗余副本，不是主键。
   */
  siteId: string;
  urlHost: string;
  urlPath?: string;
  /**
   * 规范化后的查询参数（无参数为空串）。
   *
   * 与 {@link urlFragment} 一起构成 canonical 四元组的后半截，不是可省的细节：少了它们，
   * `/watch?v=A` 与 `/watch?v=B` 在后台看起来是同一行 —— 而拆开这两者正是后端
   * DeepLinkSplitRepair 干的事。
   */
  urlQuery?: string;
  /** 路由型 fragment（`#/…` / `#!…`），页内锚点不存 */
  urlFragment?: string;
  urlScheme: string;
  /**
   * 链接类型。**非 `DOMAIN` 的书签不参与抓取**（后端 ScrapeTargetGuard 直接拒绝），
   * 因此它们的标题/图标/元数据永远为空 —— 那不是抓取失败，是我方主动不抓。
   * 后台据此收起对这类书签无意义的展示与操作，见 `views/bookmark/linkType.ts`。
   */
  linkType?: SiteLinkType;
  appName?: string;
  title?: string;
  description?: string;
  // 该书签声明的全部图片资产（一行一图），后台刻意展示全部以便排查
  assets: SiteAsset[];
  // 各展示模式下规则实际选出的渲染结果
  iconRenders: IconRender[];
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

  /**
   * 最早收录该书签的用户。
   *
   * 书签是全站共享的规范化记录，没有「属主」——归属只存在于用户与书签的关联表。这里给的是
   * 最早把它加进来的那一个，配合 {@link ownerCount} 才能看出它究竟是一个人的私藏还是热门站点。
   */
  owner?: null | UserAdminVO;
  /** 收录该书签的用户数(按用户去重)。后端恒有值，无人收录时为 0 */
  ownerCount: number;

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

  /** 最近一次抓取留下的原样元数据；从未抓成功过时为空 */
  pageMeta?: PageMetaVO;
}

/** 管理员手工改过、自动抓取不允许覆盖的字段 */
import type { BookmarkLockedField } from '#/api/enums.generated';

export type { BookmarkLockedField };

export interface BookmarkSearchParams {
  name?: string;
  status?: BookmarkParseStatus;
  /**
   * 只看该站点下的页面（站点→页面的层级下钻）。
   *
   * 与把域名塞进 {@link name} 不是一回事：那条是子串模糊匹配，用 `qq.com` 下钻会把
   * `xxqq.com.cn` 一并捞进来。下钻要的是精确的父子关系，不是搜索。
   */
  siteId?: string;
  /**
   * 按所属站点的链接类型过滤。
   *
   * 类型是站点层的事实（`site.link_type`），页面表里没有这一列 —— 所以它必须由服务端做：
   * 拿到 20 行再在前端筛掉本地/IP，分页总数和页码都会对不上。
   */
  linkType?: SiteLinkType;
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
  /** appName 传空串 = 清空并解锁；不传 = 不修改。它是 TILE 标题的主要来源，不是图标设置 */
  data: { appName?: string; title?: string; description?: string },
) {
  return requestClient.post<BookmarkEntity>(
    `/admin/bookmark/${bookmarkId}/update`,
    data,
  );
}
