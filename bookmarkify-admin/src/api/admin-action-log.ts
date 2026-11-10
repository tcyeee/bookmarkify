import { requestClient } from '#/api/request';

/**
 * 后台操作审计（系统管理 › 操作审计）。
 *
 * 与「系统配置 › 变更记录」(config_change_log) 是同一件事的两半：那张只记 system_config 的
 * 写入（需要完整新旧 JSON 做 diff），这张记其余所有带 @AdminAudit 的后台动作 ——
 * 删页面、下架分享、改 NSFW、跑孤儿清理、重算图标判定、后台登录。
 *
 * 失败的操作也会落一行（success=false + errorMsg）：「试过但没成功」与「没试过」是两件事。
 */
export interface AdminActionLogVO {
  id: string;
  /** 动作中文名，来自后端 @AdminAudit("…") */
  action: string;
  /** 控制器类名.方法名，排查时用来定位代码 */
  handler: string;
  httpMethod?: null | string;
  path?: null | string;
  /** 被操作对象 id（第一个路径变量） */
  targetId?: null | string;
  /** 入参摘要(JSON)；password/token/secret 等字段在服务端写入前已打码 */
  params?: null | string;
  success: boolean;
  errorMsg?: null | string;
  operatorId?: null | string;
  /** 操作时的昵称快照 */
  operatorName?: null | string;
  durationMs: number;
  /** 同一串也打在服务端文本日志每一行上，可据此捞出该次请求的全部现场 */
  requestId?: null | string;
  createTime: string;
}

export interface AdminActionLogSearchParams {
  /** 动作名 / 路径 / 操作者昵称 模糊匹配 */
  keyword?: string;
  onlyFailed?: boolean;
  operatorId?: string;
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

export async function getAdminActionLogListApi(params: AdminActionLogSearchParams) {
  return requestClient.post<PageResult<AdminActionLogVO>>('/admin/action-log/all', params);
}
