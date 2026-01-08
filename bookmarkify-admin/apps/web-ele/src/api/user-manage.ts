import { requestClient } from '#/api/request';

export interface UserAdminVO {
  id: string;
  nickName: string;
  deviceId: string;
  email?: string | null;
  phone?: string | null;
  role: string;
  deleted: boolean;
  disabled: boolean;
  verified: boolean;
  createTime: string;
  updateTime: string;
}

export interface UserSearchParams {
  name?: string;
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

export async function getAdminUserListApi(params: UserSearchParams) {
  return requestClient.post<PageResult<UserAdminVO>>('/admin/user/all', params);
}

