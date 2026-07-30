import { requestClient } from '#/api/request';

/**
 * 探测结论。UNKNOWN = 我方链路（抓取服务/鉴权/限流）有问题，这一轮对该站点没有结论，
 * 不会改动书签状态 —— 与「站点确实失联(DEAD)」是两回事。
 */
export type PingOutcome = 'ALIVE' | 'DEAD' | 'UNKNOWN';

export interface BookmarkPingLogVO {
  id: string;
  bookmarkId: string;
  urlHost: string;
  outcome: PingOutcome;
  /** outcome 为 UNKNOWN 时为 null */
  alive: boolean | null;
  triggeredParse: boolean;
  createTime: string;
}

export interface BookmarkPingLogSearchParams {
  urlHost?: string;
  outcome?: PingOutcome;
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

export async function getAdminBookmarkPingLogListApi(params: BookmarkPingLogSearchParams) {
  return requestClient.post<PageResult<BookmarkPingLogVO>>('/admin/bookmark-ping-log/all', params);
}
