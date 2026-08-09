import { requestClient } from '#/api/request';

export interface ScrapperCallLogVO {
  id: string;
  url: string;
  urlHost: string;
  success: boolean;
  httpStatus?: number | null;
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
