import type { PageResult } from '#/api/bookmark-ping-log';

import { requestClient } from '#/api/request';

/**
 * 一轮活性巡检的汇总。
 *
 * 与 `bookmark-ping-log` 是两个粒度：那边一次探测一行，回答「这个域名最近怎么样」；
 * 这边一轮一行，回答「巡检系统本身怎么样」。
 */
export interface BookmarkSweepLogVO {
  id: string;
  /** retryUnreachableBookmarks / livenessCheckStaleBookmarks / reviveArchivedBookmarks */
  taskLabel: string;
  /** 本轮实际处理的候选数（已按 LIMIT 截断） */
  candidates: number;
  /**
   * 到期候选总数，不含 LIMIT，也不含非域名类型的过滤。
   *
   * 判断「检测间隔追不上数据量」要拿它和 `batchSize` 比，**不能**和 `candidates` 比：后者还扣掉了
   * 本地地址/IP 这类不该探测的记录，只要本轮混进一条，`backlog > candidates` 就恒成立。
   */
  backlog: number;
  /** 本轮的单次处理上限（LIMIT），即 backlog 该对比的阈值。null = 2026-08-08 之前的历史行 */
  batchSize: null | number;
  /** 真正发起了探测的条数，也是熔断判据的分母 */
  probed: number;
  /** 被站点层短路、直接复用上一轮站点结论的条数 */
  shortCircuited: number;
  /** 短路的那部分里结论为 DEAD 的条数（其余为 UNKNOWN，不会有 ALIVE）。null = 历史行 */
  shortCircuitedDead: null | number;
  aliveCount: number;
  /** 判定失联的条数，**含**站点层短路复用的结论；其中短路的部分见 shortCircuitedDead */
  deadCount: number;
  /** 无结论的条数，**含**站点层短路复用的结论 */
  unknownCount: number;
  triggeredParse: number;
  /** 想重新抓取但因解析队列余量不足被推迟到下一轮的条数 */
  deferredParse: number;
  /** 非空即「本轮被熔断中止，没有改动任何书签」 */
  breakerReason: null | string;
  durationMs: number;
  createTime: string;
}

export interface BookmarkSweepLogSearchParams {
  taskLabel?: string;
  /** 只看被熔断中止的轮次 */
  onlyBreaker?: boolean;
  currentPage?: number;
  pageSize?: number;
}

/** 巡检健康摘要，告警条的数据源 */
export interface SweepHealthVO {
  windowHours: number;
  roundCount: number;
  breakerCount: number;
  deferredParse: number;
  latestBreaker: BookmarkSweepLogVO | null;
  /**
   * 最近一轮巡检的时间。距今过久说明巡检压根没在跑（调度线程卡死/巡检锁没释放），
   * 那种情况下熔断次数恒为 0，只看熔断数看不出来。
   */
  lastRoundAt: null | string;
}

/** 巡检任务的中文名。后端的 taskLabel 是方法名，直接显示对运维不友好 */
export const SWEEP_TASK_LABELS: Record<string, string> = {
  livenessCheckStaleBookmarks: '存活巡检',
  retryUnreachableBookmarks: '失联重试',
  // 该任务已于 2026-08-07 移除（归档改为终态，复活入口改为「有用户重新添加该网址」）。
  // 保留映射是为了让历史 sweep_log 行仍能显示中文名，而不是退化成一个方法名。
  reviveArchivedBookmarks: '归档复活',
};

/**
 * 已下线、只会出现在历史行里的巡检任务。
 *
 * 筛选下拉里仍要留着它（90 天保留期内还查得到那些轮次），但必须标出来 —— 否则一个已经不存在的
 * 任务看上去和在跑的两个一模一样，「它怎么最近一条都没有」会被当成故障去查。
 */
export const SWEEP_TASK_RETIRED = new Set(['reviveArchivedBookmarks']);

export async function getAdminSweepLogListApi(params: BookmarkSweepLogSearchParams) {
  return requestClient.post<PageResult<BookmarkSweepLogVO>>(
    '/admin/bookmark-ping-log/sweeps',
    params,
  );
}

export async function getAdminSweepHealthApi(windowHours = 24) {
  return requestClient.get<SweepHealthVO>('/admin/bookmark-ping-log/sweep-health', {
    params: { windowHours },
  });
}
