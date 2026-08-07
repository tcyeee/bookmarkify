import { requestClient } from '#/api/request';

export interface BookmarkLivenessConfigVO {
  /** 已激活书签的活性探测间隔(小时) */
  activeCheckIntervalHours: number;
  /** 重试间隔的叠加倍数：每多失败一次，间隔乘以这个数；取 1 即固定间隔不退避 */
  abnormalBackoffMultiplier: number;
  /** 异常书签的**初次**重试间隔(小时)，同时是指数退避的基数 */
  abnormalCheckIntervalHours: number;
  /** 重试间隔的上限(小时)，退避涨到这里就不再翻倍 */
  abnormalMaxIntervalHours: number;
  /** 内容重新抓取的间隔(天)：探测「站点还在吗」比重新抓取便宜得多，两者分开配 */
  contentRefreshIntervalDays: number;
  /** 连续多少次探测失败才判定失活：单次失败可能只是我方出口抖了一下 */
  deadConfirmFailures: number;
}

/** 获取全局书签巡检配置 */
export async function getBookmarkLivenessConfigApi() {
  return requestClient.post<BookmarkLivenessConfigVO>('/admin/bookmark-liveness-config');
}

/** 保存全局书签巡检配置 */
export async function saveBookmarkLivenessConfigApi(params: BookmarkLivenessConfigVO) {
  return requestClient.post<BookmarkLivenessConfigVO>('/admin/bookmark-liveness-config/save', params);
}
