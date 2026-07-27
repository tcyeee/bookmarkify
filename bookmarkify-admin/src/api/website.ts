import { requestClient } from '#/api/request';

export interface ClassifyLinkTypeResult {
  total: number;
}

/** 对全部书签按地址重新分类 linkType(域名/本地/IP/其他) 并批量回写 */
export async function classifyBookmarkLinkTypeApi() {
  return requestClient.post<ClassifyLinkTypeResult>(
    '/admin/website/classify-link-type',
  );
}
