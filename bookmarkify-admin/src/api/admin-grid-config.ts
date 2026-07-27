import { requestClient } from '#/api/request';

export interface GridConfigVO {
  gridId: string;
  storeData: null | Record<string, any>;
}

/** 获取当前管理员在某张表格上的自定义列配置（宽度/隐藏/排序） */
export async function getAdminGridConfigApi(gridId: string) {
  return requestClient.post<GridConfigVO>(`/admin/grid-config/${gridId}`);
}

/** 保存当前管理员在某张表格上的自定义列配置（宽度/隐藏/排序） */
export async function saveAdminGridConfigApi(
  gridId: string,
  storeData: Record<string, any>,
) {
  return requestClient.post<boolean>(`/admin/grid-config/${gridId}/save`, {
    storeData,
  });
}
