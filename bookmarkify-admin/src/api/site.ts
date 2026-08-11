import type { BookmarkParseStatus, SiteAsset } from '#/api/bookmark';
import type { PageResult } from '#/api/bookmark-ping-log';

import { requestClient } from '#/api/request';

/** 链接类型：本地/IP 类型的站点不会再被抓取网站信息，前端只展示统一图标 */
import type { BookmarkLinkType as SiteLinkType } from '#/api/enums.generated';

export type { SiteLinkType };

/** 站点级人工锁定字段：锁住的字段不会被下一轮自动抓取覆盖 */
import type { SiteLockedField } from '#/api/enums.generated';

export type { SiteLockedField };

/**
 * 站点（域名）视图 —— **一个域名一行**。
 *
 * 与 `BookmarkEntity`（一个页面一行）是两个层，不是详略两版：同域名下的 1000 个视频页
 * 会把域名层的问题（品牌名没抓到、整站被判 NSFW、域名不可达）完全淹没。
 */
export interface SiteAdminVO {
  id: string;
  /** 域名(含端口) */
  host: string;
  scheme: string;
  /** 站点首页地址 */
  rootUrl: string;
  linkType: SiteLinkType;

  /** 站点全名(og:site_name / manifest.name) */
  brandName?: string;
  /** 站点短名(manifest.short_name)，磁贴文案用 */
  shortName?: string;
  /** 展示用站点名：短名 → 全名 → 域名 */
  displayName: string;

  nsfw: boolean;
  /** NSFW 判定理由；CLEAN 表示判过且干净，为空表示还没判过 */
  nsfwReason?: string;

  /** 域名是否可达（与页面级活性分开：域名活着而具体页面 404 是常态） */
  isAlive: boolean;
  lastCheckAt?: string;
  nextCheckAt?: string;
  consecutiveFail: number;

  verifyFlag: boolean;
  lockedFields: SiteLockedField[];

  createTime: string;
  updateTime?: string;

  /** 该站点下已收录的页面数（等于 pageStatusCounts 各项之和） */
  pageCount: number;
  /**
   * 该站点下页面按抓取状态的分布。
   *
   * 只有 `pageCount` 时站点行只能回答「这个站有多大」，回答不了「这个站烂不烂」——
   * 而后台列表的用途从来是后者。有了分布才画得出健康分段条。
   */
  pageStatusCounts?: Partial<Record<BookmarkParseStatus, number>>;
  /** 该站点的图标资产（favicon/logo，已签名） */
  assets: SiteAsset[];
}

/** 非 SUCCESS 的页面数：站点值不值得下钻，看的就是这个数 */
export function abnormalPageCount(site: Pick<SiteAdminVO, 'pageStatusCounts'>) {
  const counts = site.pageStatusCounts ?? {};
  return Object.entries(counts).reduce(
    (sum, [status, n]) => (status === 'SUCCESS' ? sum : sum + (n ?? 0)),
    0,
  );
}

export interface SiteSearchParams {
  /** 域名 / 站点全名 / 站点短名 模糊匹配 */
  keyword?: string;
  linkType?: SiteLinkType;
  nsfw?: boolean;
  /** 域名是否可达 */
  alive?: boolean;
  verifyFlag?: boolean;
  /** 仅看品牌名为空的站点 */
  brandNameEmpty?: boolean;
  /** 连续探测失败次数下限(含) */
  minConsecutiveFail?: number;
  createTimeStart?: string;
  createTimeEnd?: string;
  /** createTime / updateTime / lastCheckAt / consecutiveFail / host */
  sortField?: string;
  sortAsc?: boolean;
  currentPage?: number;
  pageSize?: number;
}

/** 全部站点，一个域名一行 */
export async function getSiteListApi(params: SiteSearchParams) {
  return requestClient.post<PageResult<SiteAdminVO>>('/admin/site/all', params);
}

/**
 * 单个站点；站点已被清理时返回 `null`（而不是抛错）。
 *
 * 合并视图带着 `?siteId=` 直接进来时，那个站点未必落在左侧列表的当前分页里，
 * 摘要条不能只靠列表命中。
 *
 * 「站点不存在」与「请求失败」必须由调用方分开处理：前者该把 URL 上的陈旧 id 摘掉，
 * 后者只是一次网络抖动，摘掉就等于悄悄吞了用户的深链。所以前者走返回值、后者走异常。
 */
export async function getSiteDetailApi(siteId: string) {
  return requestClient.get<null | SiteAdminVO>(`/admin/site/${siteId}`);
}

/**
 * 手工编辑站点信息。**部分更新**：只送用户真正动过的字段，`undefined` 表示不改。
 *
 * 注意 `brandName` / `shortName` 的空串与 `undefined` 是两个意思：空串 =「清空并交回抓取
 * 托管」（后端同时解锁该字段），`undefined` = 「这次没动它」。想清空就必须显式送 `''`。
 */
export interface SiteBasicInfoUpdateParams {
  /** 站点全名；空串表示清空并交回抓取托管 */
  brandName?: string;
  /** 站点短名；空串表示清空并交回抓取托管 */
  shortName?: string;
  /** 人工认证：置 true 后任何抓取都不再覆盖品牌名与图标 */
  verifyFlag?: boolean;
  /** 人工改写 NSFW 结论（纠正 AI 误判） */
  nsfw?: boolean;
  /** NSFW 理由，仅在 nsfw 一并传入时生效 */
  nsfwReason?: string;
  /** 显式解锁的字段：表示接受抓取值，此后允许被自动覆盖 */
  unlockFields?: SiteLockedField[];
}

/**
 * 保存站点编辑，返回改完之后的完整快照。
 *
 * 返回完整 VO 而不是 void，是为了让调用方就地替换列表里那一行 —— 站点在「站点管理」平表和
 * 「站点与页面」摘要条里各显示一份，各自重查会让两处显示不一致，看起来像"保存没生效"。
 *
 * 手工编辑的字段会被后端**加锁**，下一轮自动抓取不再覆盖；把名字清成空串则同时解锁。
 */
export async function updateSiteBasicInfoApi(siteId: string, params: SiteBasicInfoUpdateParams) {
  return requestClient.post<SiteAdminVO>(`/admin/site/${siteId}`, params);
}
