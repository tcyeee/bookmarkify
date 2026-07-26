import { requestClient } from '#/api/request';

export interface ScrapperCallLogVO {
  id: string;
  url: string;
  urlHost: string;
  success: boolean;
  httpStatus?: number | null;
  source?: string | null;
  cached?: boolean | null;
  durationMs: number;
  errorMsg?: string | null;
  createTime: string;
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
