import type { UserBehaviorType } from '#/api/enums.generated';

import { requestClient } from '#/api/request';

export type { UserBehaviorType };

export interface UserBehaviorLogVO {
  id: string;
  uid: string;
  /** 行为发生时的昵称快照；用户之后改名不影响历史记录 */
  nickNameSnapshot?: null | string;
  behaviorType: UserBehaviorType;
  /** 行为详情，如 URL / 文件名+条数 / 令牌备注 */
  detail?: null | string;
  createTime: string;
}

export interface UserBehaviorLogSearchParams {
  /** 昵称快照模糊匹配 或 uid 精确匹配 */
  keyword?: string;
  behaviorType?: UserBehaviorType;
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

export async function getAdminUserBehaviorLogListApi(params: UserBehaviorLogSearchParams) {
  return requestClient.post<PageResult<UserBehaviorLogVO>>('/admin/user-behavior-log/all', params);
}

/** 行为类型释义：筛选下拉与表格标签共用 */
export const USER_BEHAVIOR_TYPE_DESC: Record<
  UserBehaviorType,
  { desc: string; label: string; type: 'info' | 'primary' | 'success' | 'warning' }
> = {
  ADD_BOOKMARK: {
    label: '新增书签',
    type: 'success',
    desc: '通过 URL 新增一条书签',
  },
  PUBLISH_SHARE: {
    label: '发布书签集',
    type: 'primary',
    desc: '创建并发布一个书签分享(书签集)',
  },
  IMPORT_BOOKMARK: {
    label: '导入书签',
    type: 'warning',
    desc: '从文件批量导入书签',
  },
  CREATE_ACCESS_TOKEN: {
    label: '生成令牌',
    type: 'info',
    desc: '生成一个新的插件访问令牌',
  },
  QUERY_BY_TOKEN: {
    label: '令牌查询',
    type: 'info',
    desc: '插件持令牌查询网站信息，是 X-Extension-Token 唯一的取数接口',
  },
};
