import { requestClient } from '#/api/request';

export interface BookmarkLivenessConfigVO {
  activeCheckIntervalHours: number;
  abnormalCheckIntervalHours: number;
}

/** 获取全局书签活性检查频率配置 */
export async function getBookmarkLivenessConfigApi() {
  return requestClient.post<BookmarkLivenessConfigVO>('/admin/bookmark-liveness-config');
}

/** 保存全局书签活性检查频率配置(小时) */
export async function saveBookmarkLivenessConfigApi(params: BookmarkLivenessConfigVO) {
  return requestClient.post<BookmarkLivenessConfigVO>('/admin/bookmark-liveness-config/save', params);
}
