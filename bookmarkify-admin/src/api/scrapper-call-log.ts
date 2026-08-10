import { requestClient } from '#/api/request';

export interface ScrapperCallLogVO {
  id: string;
  url: string;
  urlHost: string;
  success: boolean;
  /** scrapper 回给我方的状态码，**不是**目标站点的：FETCH_FAILED 恒 502、TIMEOUT 恒 504 */
  httpStatus?: number | null;
  /** scrapper 错误码，成功时为空。见 SCRAPPER_ERROR_CODE_DESC */
  errorCode?: null | string;
  /**
   * 目标站点自己的最终 HTTP 状态码（取自错误响应的 fetch.httpStatus）。
   * 「403/406/412 被反爬拦下」和「连不上/DNS 挂了」处置完全相反，而只有这一列分得开。
   * 没连上目标时本就没有状态码，为空是事实。
   */
  targetStatus?: null | number;
  source?: string | null;
  cached?: boolean | null;
  /** 实际抓取层：HTTP=Layer1 普通 HTTP，HEADLESS=Layer2 无头浏览器，SITE_API=站点官方 API 救援；失败时为空 */
  layerUsed?: string | null;
  durationMs: number;
  errorMsg?: string | null;
  createTime: string;
  /**
   * 该域名的站点图标，后端按 urlHost 反查 site_asset 后签出的我方 OSS 地址。
   * 为空表示我方从没抓到过这个站的图标 —— 此时用本地兜底图，不要去外站现抓。
   */
  faviconUrl?: null | string;
}

export interface ScrapperCallLogSearchParams {
  urlHost?: string;
  success?: boolean;
  /**
   * 调用时间下界/上界（含），`YYYY-MM-DD HH:mm:ss`。
   *
   * 给「从巡检轮次跳过来看这一轮触发的重抓」用：那些重抓是异步投递的，日志表里既没有轮次 ID 也没有
   * 页面 ID，只能靠时间窗圈。上界要比轮次结束时刻宽出一截 —— 重抓排在解析池里，落库晚于巡检收工。
   */
  createTimeFrom?: string;
  createTimeTo?: string;
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

export async function getAdminScrapperCallLogListApi(params: ScrapperCallLogSearchParams) {
  return requestClient.post<PageResult<ScrapperCallLogVO>>('/admin/scrapper-call-log/all', params);
}

// ── 失败站点排行 ──

export interface ScrapperErrorCodeCountVO {
  /** 迁移前的历史行没有错误码，后端统一归为 UNKNOWN */
  errorCode: string;
  count: number;
}

export interface ScrapperFailedHostVO {
  urlHost: string;
  totalCalls: number;
  failedCalls: number;
  /** 失败落在多少个不同的地址上。1 = 只有一个页面在反复失败；接近 failedCalls = 整站都抓不动 */
  failedUrls: number;
  /** 失败调用的累计耗时，也就是"浪费" —— 排行默认按它排 */
  failedDurationMs: number;
  totalDurationMs: number;
  lastFailedAt?: null | string;
  /**
   * 窗口内最近一次**成功**的时间，为空表示这个窗口里一次都没成功过。
   * 判断「这个域名是不是彻底抓不动」的关键：根路径 200、内容页 412 是常见形态，
   * 此时失败率很高但站点本身完全正常，按域名屏蔽会误伤。
   */
  lastSuccessAt?: null | string;
  lastFailedUrl?: null | string;
  lastErrorMsg?: null | string;
  lastErrorCode?: null | string;
  lastTargetStatus?: null | number;
  lastLayerUsed?: null | string;
  errorBreakdown: ScrapperErrorCodeCountVO[];
  /** 我方 OSS 签名地址；为空时用本地兜底图，不要按 host 拼 favicon.ico 直连外站 */
  faviconUrl?: null | string;
}

export type FailedHostSortField =
  | 'failedCalls'
  | 'failedDurationMs'
  | 'failRate';

export interface ScrapperFailedHostParams {
  /** 统计窗口天数，后端收紧到 1~365 */
  days?: number;
  /** 失败次数门槛(含)，低于它的域名不进榜 */
  minFailures?: number;
  sortField?: FailedHostSortField;
  limit?: number;
}

/**
 * 失败站点排行。**不分页** —— 这是一张排行榜，翻到第 340 名对「哪几个站点最值得处理」
 * 这个问题没有意义。要看某个域名的全部记录，下钻到调用日志页。
 */
export async function getAdminFailedHostRankingApi(params: ScrapperFailedHostParams) {
  return requestClient.post<ScrapperFailedHostVO[]>(
    '/admin/scrapper-call-log/failed-host-ranking',
    params,
  );
}

/**
 * 错误码释义。这一列是整个页面的重心：失败率相同的两个域名，处置可能完全相反。
 *
 * `blame` 说的是「该找谁」——按站点归因 vs 我方问题。把我方问题的行按站点排进榜里
 * 本身没错（它确实发生在这个域名上），但据此得出「这个站点抓不动」的结论就是错的。
 */
export const SCRAPPER_ERROR_CODE_DESC: Record<
  string,
  { blame: 'ours' | 'site'; desc: string; label: string }
> = {
  CONTRACT_MISMATCH: {
    blame: 'ours',
    desc: 'scrapper 的响应解析不了：API 与 scrapper 两侧代码不同步。与目标站点无关，每个站点都会中',
    label: '契约不匹配',
  },
  FETCH_FAILED: {
    blame: 'site',
    desc: '取回失败。它同时覆盖「连不上/DNS 解析失败」和「连上了但被拒（403/406/412）」两类——分清它们要看「目标状态码」列，前者为空，后者有码',
    label: '取回失败',
  },
  FORBIDDEN_TARGET: {
    blame: 'ours',
    desc: '我方主动不抓：内网地址，或目标是裸 IP / localhost 而非域名。正常情况下 API 侧的 ScrapeTargetGuard 已经拦掉了，能走到这里说明两侧规则不一致（比如站点 302 到了一个 IP）',
    label: '拒绝抓取',
  },
  HEADLESS_FAILED: {
    blame: 'site',
    desc: '无头浏览器也打不开这一页（拦截页、登录墙 SPA）。这是关于站点的结论，scrapper 会据此把该 host 熔断 24 小时',
    label: '无头失败',
  },
  HEADLESS_UNAVAILABLE: {
    blame: 'ours',
    desc: '我方无头能力当下不可用：Chrome 起不来、CDP 挂了、或并发名额等不到。与 HEADLESS_FAILED 只差一个词，含义相反——那个是站点打不开，这个是我们打不开任何页面',
    label: '无头不可用',
  },
  INVALID_URL: {
    blame: 'ours',
    desc: '地址格式非法，一个字节都没发出去',
    label: '地址非法',
  },
  OSS_FAILED: {
    blame: 'ours',
    desc: '页面抓到了，图片上传我方 OSS 时失败',
    label: 'OSS 失败',
  },
  RECENTLY_FAILED: {
    blame: 'site',
    desc: '命中 scrapper 的负缓存（60s）：这条 URL 刚刚抓失败过。同一网址被两个用户先后添加就会撞上，它本身不代表额外的失败',
    label: '负缓存命中',
  },
  SCRAPPER_UNREACHABLE: {
    blame: 'ours',
    desc: '连不上抓取服务本身，scrapper 一个字节都没回。这是我方自造的码，不属于 scrapper 契约',
    label: '抓取服务连不上',
  },
  TIMEOUT: {
    blame: 'site',
    desc: '目标站点在预算内没有响应完',
    label: '超时',
  },
  UNKNOWN: {
    blame: 'ours',
    desc: '这条记录早于 2026-08-10 的错误码迁移，当时没有记录败因。不是一种真实的失败类型',
    label: '未记录',
  },
};
