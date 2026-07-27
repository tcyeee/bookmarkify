import { requestClient } from '#/api/request';

export interface ClassifyLinkTypeResult {
  total: number;
  /** 同时批量检查过的书签总数(NSFW 检查) */
  nsfwChecked: number;
  /** 本次命中 NSFW(涉黄/涉赌等) 的书签数 */
  nsfwFlagged: number;
}

/** 对全部书签按地址重新分类 linkType(域名/本地/IP/其他) 并批量回写；同时批量检查全部书签是否 NSFW */
export async function classifyBookmarkLinkTypeApi() {
  return requestClient.post<ClassifyLinkTypeResult>(
    '/admin/website/classify-link-type',
  );
}

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
}

/** 任意 URL 活性检测：不要求该 URL 已收录为书签，直接调用 scrapper 抓取一次并返回其全部字段 */
export async function checkWebsiteLivenessApi(url: string) {
  return requestClient.post<WebsiteLivenessCheckResult>(
    '/admin/website/liveness-check',
    { url },
  );
}
