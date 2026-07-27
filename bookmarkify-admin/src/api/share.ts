import { requestClient } from '#/api/request';

export interface UserShareAdminVO {
  id: string;
  uid: string;
  nickName: string;
  note?: string;
  expireTime?: string | null;
  status: string;
  bookmarkCount: number;
  createTime: string;
}

export interface ShareSearchParams {
  uid?: string;
  status?: string;
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

/** 分页查询全部用户分享 */
export async function getAdminShareListApi(params: ShareSearchParams) {
  return requestClient.post<PageResult<UserShareAdminVO>>(
    '/admin/share/all',
    params,
  );
}

/** 强制下架某个分享 */
export async function takeDownShareApi(id: string) {
  return requestClient.post<boolean>(`/admin/share/takedown?id=${id}`);
}
