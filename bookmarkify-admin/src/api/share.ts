import { requestClient } from '#/api/request';

export interface UserShareAdminVO {
  id: string;
  uid: string;
  nickName: string;
  note?: string;
  expireTime?: string | null;
  status: string;
  rejectReason?: string | null;
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

export interface ShareBookmarkVO {
  bookmarkId?: string;
  pageId?: string;
  title?: string;
  description?: string;
  urlFull?: string;
  /** 图标签名地址；走首字母色块时为 null */
  iconUrl?: null | string;
  linkType?: string;
  /** 站点被判定为疑似违规 */
  nsfw?: boolean;
}

export interface ShareDetailVO {
  share: UserShareAdminVO;
  bookmarks: ShareBookmarkVO[];
}

/** 查看某个分享的详情(含其包含的全部书签)；分享不存在时返回 null */
export async function getAdminShareDetailApi(id: string) {
  return requestClient.get<null | ShareDetailVO>('/admin/share/detail', {
    params: { id },
  });
}

/** 强制下架某个分享 */
export async function takeDownShareApi(id: string) {
  return requestClient.post<boolean>(`/admin/share/takedown?id=${id}`);
}
