import type { BookmarkParseStatus, SiteAsset } from '#/api/bookmark';
import type { PageResult } from '#/api/bookmark-ping-log';

import { requestClient } from '#/api/request';

/** 链接类型：本地/IP 类型的站点不会再被抓取网站信息，前端只展示统一图标 */
export type SiteLinkType = 'DOMAIN' | 'IP' | 'LOCAL' | 'OTHER';

/** 站点级人工锁定字段：锁住的字段不会被下一轮自动抓取覆盖 */
export type SiteLockedField = 'BRAND_NAME' | 'SHORT_NAME';

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
 * 单个站点。
 *
 * 合并视图带着 `?siteId=` 直接进来时，那个站点未必落在左侧列表的当前分页里，
 * 摘要条不能只靠列表命中。站点已被清理时后端返回 404。
 */
export async function getSiteDetailApi(siteId: string) {
  return requestClient.get<SiteAdminVO>(`/admin/site/${siteId}`);
}
