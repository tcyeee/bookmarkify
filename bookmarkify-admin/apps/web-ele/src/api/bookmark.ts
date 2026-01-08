import { requestClient } from '#/api/request';

export interface BookmarkEntity {
  id: string;
  urlHost: string;
  urlPath?: string;
  urlScheme: string;
  appName?: string;
  title?: string;
  description?: string;
  iconBase64?: string;
  maximalLogoSize: number;
  parseStatus: 'LOADING' | 'SUCCESS' | 'CLOSED' | 'BLOCKED'; // Add other statuses as needed
  isActivity: boolean;
  parseErrMsg?: string;
  createTime: string;
  updateTime?: string;
}

export interface BookmarkSearchParams {
  name?: string;
  status?: 'LOADING' | 'SUCCESS' | 'CLOSED' | 'BLOCKED';
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
