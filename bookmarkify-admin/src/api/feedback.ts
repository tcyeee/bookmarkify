import { requestClient } from '#/api/request';

/**
 * 网站反馈组件后台接口。
 *
 * 对应的**公开**提交接口是 `POST /api/feedback/submit`（无需登录，调用方是外部 Agent），
 * 这里只封装后台侧的收件箱与「所属产品」维护。
 */

export interface FeedbackVO {
  id: string;
  target: string;
  email?: null | string;
  content: string;
  read: boolean;
  sourceIp?: null | string;
  userAgent?: null | string;
  createTime: string;
  readTime?: null | string;
}

export interface FeedbackTargetVO {
  id: string;
  name: string;
  sort: number;
  /** 该产品下的反馈总数（仅 targets 列表接口返回） */
  total?: number;
  /** 该产品下的未读数 */
  unread?: number;
}

export interface FeedbackSearchParams {
  target?: string;
  /** null / undefined = 全部 */
  read?: boolean;
  keyword?: string;
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

export async function getFeedbackListApi(params: FeedbackSearchParams) {
  return requestClient.post<PageResult<FeedbackVO>>('/admin/feedback/list', params);
}

export async function getFeedbackUnreadCountApi() {
  return requestClient.post<number>('/admin/feedback/unread-count');
}

/** 批量标记已读 / 未读 */
export async function markFeedbackReadApi(ids: string[], read: boolean) {
  return requestClient.post<boolean>('/admin/feedback/read', { ids, read });
}

/** 批量删除 */
export async function deleteFeedbackApi(ids: string[]) {
  return requestClient.post<boolean>('/admin/feedback/delete', { ids });
}

// ── 所属产品 ──

export async function getFeedbackTargetsApi() {
  return requestClient.post<FeedbackTargetVO[]>('/admin/feedback/targets');
}

export async function addFeedbackTargetApi(name: string) {
  return requestClient.post<FeedbackTargetVO>('/admin/feedback/targets/add', { name });
}

export async function deleteFeedbackTargetApi(id: string) {
  return requestClient.post<boolean>(`/admin/feedback/targets/${id}/delete`);
}
