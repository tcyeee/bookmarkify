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
  /**
   * 失活网站最大重试次数：连续失败到这个数就归档，**此后不再有任何定时任务碰它**。
   * 唯一的复活入口是有用户重新添加该网址（就地清零重试次数并重新检查）。
   */
  maxRetryFailures: number;
}

/**
 * 字段名 → 中文名与单位，给「变更记录」把 `maxRetryFailures: 10 → 5` 显示成人话用。
 *
 * 与设置页各行的 label 是同一套措辞，改一处时记得改另一处 —— 刻意没有让设置行也从这里取：
 * 那会把每项配置的 label/单位/说明拆到两个文件里，而设置行的价值恰恰在于这些文案就写在控件旁边。
 * 这里对不上只是变更记录里的措辞变旧，不影响任何行为。
 */
export const LIVENESS_FIELD_LABELS: Record<string, { label: string; unit: string }> = {
  abnormalBackoffMultiplier: { label: '重试叠加倍数', unit: '倍' },
  abnormalCheckIntervalHours: { label: '初次重试间隔', unit: '小时' },
  abnormalMaxIntervalHours: { label: '最长重试间隔', unit: '小时' },
  activeCheckIntervalHours: { label: '已激活书签的检测频率', unit: '小时' },
  contentRefreshIntervalDays: { label: '内容重新抓取间隔', unit: '天' },
  deadConfirmFailures: { label: '判定失活所需连续失败次数', unit: '次' },
  maxRetryFailures: { label: '失活网站最大重试次数', unit: '次' },
};

/** 获取全局书签巡检配置 */
export async function getBookmarkLivenessConfigApi() {
  return requestClient.post<BookmarkLivenessConfigVO>('/admin/bookmark-liveness-config');
}

/** 保存全局书签巡检配置 */
export async function saveBookmarkLivenessConfigApi(params: BookmarkLivenessConfigVO) {
  return requestClient.post<BookmarkLivenessConfigVO>('/admin/bookmark-liveness-config/save', params);
}
