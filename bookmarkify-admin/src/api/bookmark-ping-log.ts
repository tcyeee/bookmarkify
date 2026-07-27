import { requestClient } from '#/api/request';

export interface BookmarkPingLogVO {
  id: string;
  bookmarkId: string;
  urlHost: string;
  alive: boolean;
  triggeredParse: boolean;
  createTime: string;
}

export interface BookmarkPingLogSearchParams {
  urlHost?: string;
  alive?: boolean;
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
