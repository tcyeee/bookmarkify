import { requestClient } from '#/api/request';

/** 一个字段的前后取值 */
export interface ConfigFieldChangeVO {
  field: string;
  newValue: null | string;
  oldValue: null | string;
}

export interface ConfigChangeLogVO {
  /** 本次真正变化的字段，服务端算好的 */
  changes: ConfigFieldChangeVO[];
  configKey: string;
  createTime: string;
  id: string;
  /** 该组配置的首次写入(此前库中没有这一行) */
  initial: boolean;
  newValue: string;
  oldValue: null | string;
  operatorId: null | string;
  operatorName: null | string;
}

export interface ConfigChangeLogSearchParams {
  configKey?: string;
  currentPage?: number;
  pageSize?: number;
}

/** 配置组的中文名。key 来自后端的 CONFIG_KEY 常量，新增配置组时补一条 */
export const CONFIG_KEY_LABELS: Record<string, string> = {
  bookmark_liveness_check_frequency: '书签巡检',
};

export interface PageResult<T> {
  current: number;
  pages: number;
  records: T[];
  size: number;
  total: number;
}

/** 系统配置变更记录（只读；写入在 API 侧的 JsonConfigAccessor.update） */
export async function getConfigChangeLogListApi(params: ConfigChangeLogSearchParams) {
  return requestClient.post<PageResult<ConfigChangeLogVO>>('/admin/config-change-log/all', params);
}
