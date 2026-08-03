import { requestClient } from '#/api/request';

/** 网站活性检测结果：直接透传 scrapper /scrape 返回的全部原始字段 */
export interface WebsiteLivenessCheckResult {
  success: boolean;
  title?: string;
  description?: string;
  image?: string;
  favicon?: string;
  logo?: string;
  source?: string;
  cached?: boolean;
  screenshot?: string;
  errorMsg?: string;
  /** 抓取成功且该 URL 命中已有书签时为 true，表示已同步覆盖持久化该书签 */
  synced?: boolean;
}

/** 任意 URL 活性检测：不要求该 URL 已收录为书签，直接调用 scrapper 抓取一次并返回其全部字段 */
export async function checkWebsiteLivenessApi(url: string) {
  return requestClient.post<WebsiteLivenessCheckResult>(
    '/admin/website/liveness-check',
    { url },
  );
}
