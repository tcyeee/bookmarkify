import { requestClient } from '#/api/request';

/** 业务场景，与后端 AiCallScene 枚举一一对应 */
export type AiCallScene =
  | 'APP_NAME'
  | 'CATEGORY_INFER'
  | 'CATEGORY_PROPOSE'
  | 'CONTENT_REVIEW'
  | 'NSFW_CHECK'
  | 'SIMILAR_SITE';

export interface AiCallLogVO {
  id: string;
  provider: string;
  scene: AiCallScene;
  model?: null | string;
  subject?: null | string;
  success: boolean;
  httpStatus?: null | number;
  requestBody?: null | string;
  responseBody?: null | string;
  promptTokens?: null | number;
  completionTokens?: null | number;
  totalTokens?: null | number;
  durationMs: number;
  errorMsg?: null | string;
  /**
   * 产生这一行的那次请求的短 id（响应头 X-Request-Id，也打在服务端每行文本日志上）。
   * 拿它去 `docker logs bookmarkify-api | grep <requestId>` 能捞出那次请求的全部现场 ——
   * 账本与文本日志从前没有任何共同键，这一列就是补上的那把钥匙。
   */
  requestId?: null | string;
  createTime: string;
}

export interface AiCallLogSearchParams {
  subject?: string;
  scene?: AiCallScene;
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

export async function getAdminAiCallLogListApi(params: AiCallLogSearchParams) {
  return requestClient.post<PageResult<AiCallLogVO>>('/admin/ai-call-log/all', params);
}
