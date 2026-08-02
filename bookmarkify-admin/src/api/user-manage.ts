import { requestClient } from '#/api/request';

export interface UserAdminVO {
  id: string;
  nickName: string;
  deviceId: string;
  email?: string | null;
  phone?: string | null;
  /** 头像签名地址：私有桶里存的是 object key，后端签好才下发；无头像时为 null */
  avatarUrl?: null | string;
  /** 绑定的 Google 邮箱，未绑定为 null */
  googleEmail?: null | string;
  /** 绑定的 GitHub 用户名，未绑定为 null */
  githubLogin?: null | string;
  role: string;
  deleted: boolean;
  disabled: boolean;
  verified: boolean;
  createTime: string;
  updateTime: string;
}

/** 用户状态：由后端的 deleted / disabled 组合而成的互斥状态 */
export type UserStatus = 'DELETED' | 'DISABLED' | 'NORMAL';

export interface UserSearchParams {
  name?: string;
  status?: undefined | UserStatus;
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

