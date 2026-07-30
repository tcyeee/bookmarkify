import { requestClient } from '#/api/request';

export interface BookmarkLivenessConfigVO {
  /** 已激活书签的活性探测间隔(小时) */
  activeCheckIntervalHours: number;
  /** 异常书签的重试间隔(小时)，同时是指数退避的基数 */
  abnormalCheckIntervalHours: number;
  /** 内容重新抓取的间隔(天)：探测「站点还在吗」比重新抓取便宜得多，两者分开配 */
  contentRefreshIntervalDays: number;
}

/** 获取全局书签巡检配置 */
export async function getBookmarkLivenessConfigApi() {
  return requestClient.post<BookmarkLivenessConfigVO>('/admin/bookmark-liveness-config');
}

/** 保存全局书签巡检配置 */
export async function saveBookmarkLivenessConfigApi(params: BookmarkLivenessConfigVO) {
  return requestClient.post<BookmarkLivenessConfigVO>('/admin/bookmark-liveness-config/save', params);
}
