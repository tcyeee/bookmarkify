import { requestClient } from '#/api/request';

export interface CategoryEntity {
  id: string;
  slug: string;
  name: string;
  description?: string;
  color?: string;
  sort: number;
}

/** 获取全部分类词条 */
export async function getCategoryListApi() {
  return requestClient.post<CategoryEntity[]>('/admin/category/list');
}

/** 新增或修改一条分类（id 为空=新增） */
export async function saveCategoryApi(data: Partial<CategoryEntity>) {
  return requestClient.post<CategoryEntity>('/admin/category/save', data);
}

/** 软删一条分类 */
export async function deleteCategoryApi(id: string) {
  return requestClient.post<void>(`/admin/category/${id}/delete`);
}
