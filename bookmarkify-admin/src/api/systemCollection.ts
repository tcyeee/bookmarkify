import { requestClient } from '#/api/request';

/** 系统书签集发布流程使用的书签摘要。 */
export interface CollectionBookmark {
  pageId: string;
  rawUrl: string;
  title?: string;
  description?: string;
  iconUrl?: string | null;
}

export interface ExtractCollectionLinksResult {
  links: string[];
}

export interface StartCollectionCrawlResult {
  jobId: string;
  count: number;
}

export interface CollectionMetaResult {
  title: string;
  description: string;
}

export interface PublishSystemCollectionParams {
  title: string;
  description: string;
  pageIds: string[];
}

/** 将长文本交给 AI，提取并去重其中的原始链接。 */
export async function extractCollectionLinksApi(text: string) {
  return requestClient.post<ExtractCollectionLinksResult>(
    '/admin/bookmark/collections/system/ai/extract-links',
    { text },
  );
}

/** 创建批量抓取任务；每条链接的结果由管理员 WebSocket 推送。 */
export async function startCollectionCrawlApi(urls: string[]) {
  return requestClient.post<StartCollectionCrawlResult>(
    '/admin/bookmark/collections/system/crawl',
    { urls },
  );
}

/** 根据已抓取的书签生成集合标题和描述建议。 */
export async function generateCollectionMetaApi(bookmarks: CollectionBookmark[]) {
  return requestClient.post<CollectionMetaResult>(
    '/admin/bookmark/collections/system/ai/generate-meta',
    { bookmarks },
  );
}

/** 发布为正式的系统书签集。 */
export async function publishSystemCollectionApi(
  params: PublishSystemCollectionParams,
) {
  return requestClient.post<{ id: string }>(
    '/admin/bookmark/collections/system/publish',
    params,
  );
}
