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
