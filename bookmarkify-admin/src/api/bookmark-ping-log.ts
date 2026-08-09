import { requestClient } from '#/api/request';

/**
 * 探测结论。UNKNOWN = 我方链路（抓取服务/鉴权/限流）有问题，这一轮对该站点没有结论，
 * 不会改动书签状态 —— 与「站点确实失联(DEAD)」是两回事。
 */
export type PingOutcome = 'ALIVE' | 'DEAD' | 'UNKNOWN';

export interface BookmarkPingLogVO {
  id: string;
  /** 被探测的页面 ID（page_ping_log.page_id），与书签详情里的「书签 ID」是同一个值 */
  pageId: string;
  urlHost: string;
  /**
   * 被探测页面的完整地址，后端按 pageId 补；页面已被删除时为 null。
   *
   * 日志表本身只存 host。按轮次下钻时一屏里会有同一域名下的几十条深链，只给 host 分不清是哪一页
   * —— 而「首页 ALIVE、某条深链 DEAD」正是最常见的形态。
   */
  url: null | string;
  outcome: PingOutcome;
  /** outcome 为 UNKNOWN 时为 null */
  alive: boolean | null;
  triggeredParse: boolean;
  /** 产生这次探测的巡检轮次（sweep_log.id）。null = 2026-08-09 之前的历史行 */
  sweepId: null | string;
  createTime: string;
}

export interface BookmarkPingLogSearchParams {
  urlHost?: string;
  /**
   * 只看某一个页面的探测历史（精确匹配）。
   *
   * 与 `urlHost` 不能互相替代：按 host 筛出来的是整站流水，一个域名下几十条深链混在一起时，
   * 首页的 ALIVE 会把某条深链自己的 DEAD 淹掉。详情弹窗要的是「这一页」的历史。
   */
  pageId?: string;
  /**
   * 只看某一轮巡检探测过的页面（精确匹配），巡检轮次页的下钻抽屉用它。
   *
   * 查出来的条数等于该轮的 `probed`，**不等于** `candidates`：被站点层短路的页面本轮压根没探过，
   * 按「一次探测一行」的语义不落日志。抽屉里必须把这个差额说明白，否则会被当成漏数据。
   */
  sweepId?: string;
  outcome?: PingOutcome;
  currentPage?: number;
  pageSize?: number;
}

/**
 * 探测结论的展示口径。UNKNOWN 刻意不是「失败」的一种：它说的是我方链路这一轮没得出结论，
 * 站点状态**未被改动**——标成红色会让管理员去排查一个根本没发生的站点故障。
 */
export const PING_OUTCOME_META: Record<
  PingOutcome,
  { label: string; tip: string; type: 'danger' | 'info' | 'success' }
> = {
  ALIVE: { label: '存活', type: 'success', tip: '探测通过，本轮判定站点可访问' },
  DEAD: {
    label: '失联',
    type: 'danger',
    tip: '探测判定站点不可访问，连续失败累计到阈值后会归档',
  },
  UNKNOWN: {
    label: '无结论',
    type: 'info',
    tip: '我方链路(抓取服务/鉴权/限流)有问题，这一轮没有结论，不会改动书签状态',
  },
};

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export async function getAdminBookmarkPingLogListApi(params: BookmarkPingLogSearchParams) {
  return requestClient.post<PageResult<BookmarkPingLogVO>>('/admin/bookmark-ping-log/all', params);
}
