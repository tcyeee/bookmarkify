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

/**
 * 手动触发一轮巡检之前的「这一轮会做什么」预览。
 *
 * 每个数都是后端用与真正开跑同一套候选查询现算的，所以确认框里写的条数和跑出来的对得上。
 * 但预览与执行之间有时间差（游标会推进、站点活性会变），这些是预估不是承诺。
 */
export interface SweepPreviewVO {
  taskLabel: string;
  /** 这个任务管哪一批书签 */
  scope: string;
  /** 到期候选总数，不含 LIMIT 也不含非域名过滤 */
  backlog: number;
  batchSize: number;
  /** 本轮会处理的条数：已按 LIMIT 截断、已滤掉非域名书签 */
  candidates: number;
  /** 被 LIMIT 截断、留到下一轮的条数 */
  truncated: number;
  /** 本地地址/IP 等非域名书签，不探测 */
  skippedNonDomain: number;
  /** 预计被站点层短路（所属域名已判死）、不逐页探测的条数 */
  shortCircuited: number;
  /** 为已判死的域名补探根地址的次数 */
  rootProbes: number;
  /** 预计实际发起的探测次数，耗时的来源 */
  probes: number;
  /** 预计最多多少条会顺带触发重新抓取（异步，不计入本轮耗时） */
  mayTriggerParse: number;
  /** 本任务是否有资格把书签改判为失联 —— 确认框里最该看清的一条 */
  mayConfirmDeath: boolean;
  deadConfirmFailures: number;
  intervalHours: number;
  concurrency: number;
  estimatedMs: number;
  /** 最坏耗时：全部探测都吃满单条超时 */
  worstCaseMs: number;
  /** 预估所用的单条探测平均墙钟耗时；null = 没有历史样本，用的是默认假设 */
  sampleProbeMs: null | number;
  sampleRounds: number;
  /** 上一轮是否仍在进行。为真时触发会被巡检锁挡下 */
  running: boolean;
}

/** 手动触发的受理结果。巡检是异步的，这里只回答「收下了没有」 */
export interface SweepTriggerResultVO {
  accepted: boolean;
  message: string;
}

/** 可手动触发的巡检任务。已下线的 reviveArchivedBookmarks 不在其中（没有执行体） */
export const SWEEP_TRIGGERABLE_TASKS = [
  'livenessCheckStaleBookmarks',
  'retryUnreachableBookmarks',
] as const;

export async function getAdminSweepPreviewApi(taskLabel: string) {
  return requestClient.get<SweepPreviewVO>(
    '/admin/bookmark-ping-log/sweep-preview',
    { params: { taskLabel } },
  );
}

export async function triggerAdminSweepApi(taskLabel: string) {
  return requestClient.post<SweepTriggerResultVO>(
    '/admin/bookmark-ping-log/sweep-trigger',
    { taskLabel },
  );
}

export async function getAdminSweepHealthApi(windowHours = 24) {
  return requestClient.get<SweepHealthVO>('/admin/bookmark-ping-log/sweep-health', {
    params: { windowHours },
  });
}
