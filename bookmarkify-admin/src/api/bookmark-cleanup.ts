import { requestClient } from '#/api/request';

/**
 * 一轮书签清理的「会删什么 / 删了什么」。
 *
 * 预览与执行返回同一个结构，靠 `dryRun` 区分 —— 服务端用的也是同一段判定代码，
 * 所以确认框里的数字与真正删掉的东西是同一个查询算出来的。
 */
export interface OrphanCleanupReport {
  /** 是否只统计不删除 */
  dryRun: boolean;

  /** 无人引用且属于本地/IP 站点的页面数 */
  localIpPages: number;
  /** 无人引用且已判定失活(抓取失败/已归档)的页面数 */
  deadPages: number;
  /** 实际删除的页面数。两条规则会重叠，所以不等于上面两个相加 */
  pages: number;
  /** 命中规则但因创建时间太近(10 分钟内)而跳过的页面数 */
  skippedRecentPages: number;

  /** 实际删除的站点数 */
  sites: number;

  /* 级联清掉的附属行 */
  pageMeta: number;
  snapshots: number;
  pingLogs: number;
  pageCategories: number;
  pageAssets: number;
  siteAssets: number;
  displayPrefs: number;
  /** 随之失去引用的对象存储文件数；对象本身由下一轮 OSS 对账回收 */
  releasedFiles: number;

  durationMs: number;
}

/** 预览：这一轮会删掉什么，只统计不写库 */
export async function previewBookmarkCleanupApi() {
  return requestClient.post<OrphanCleanupReport>('/admin/bookmark-cleanup/preview');
}

/** 执行清理。无撤销路径，调用前必须先让管理员看过预览 */
export async function runBookmarkCleanupApi() {
  return requestClient.post<OrphanCleanupReport>('/admin/bookmark-cleanup/run');
}
