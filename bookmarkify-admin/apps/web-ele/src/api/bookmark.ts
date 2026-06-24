import { requestClient } from '#/api/request';

export interface CategoryVO {
  id: string;
  slug: string;
  name: string;
  color?: string;
}

export interface SimilarSite {
  name: string;
  domain: string;
  reason: string;
  /** 本地是否已收录（后端按 urlHost 归一化比对回填） */
  exists?: boolean;
}

/** 书签图标信息（后端 website_logo 表，与书签一对一） */
export interface BookmarkLogo {
  iconBase64?: string;
  logoUrl?: string;
  maximalLogoSize: number;
  iconPadding: number;
  iconBgColor?: string;
  useHdLogo: boolean;
}

export interface BookmarkEntity {
  id: string;
  urlHost: string;
  urlPath?: string;
  urlScheme: string;
  appName?: string;
  title?: string;
  description?: string;
  // 图标相关字段统一收拢到 logo（后端 website_logo 表）
  logo: BookmarkLogo;
  parseStatus: 'LOADING' | 'SUCCESS' | 'CLOSED' | 'BLOCKED'; // Add other statuses as needed
  isActivity: boolean;
  parseErrMsg?: string;
  createTime: string;
  updateTime?: string;
  categories?: CategoryVO[];
}

export interface BookmarkSearchParams {
  name?: string;
  status?: 'LOADING' | 'SUCCESS' | 'CLOSED' | 'BLOCKED';
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

/**
 * 获取全部书签信息（分页）
 */
export async function getBookmarkListApi(params: BookmarkSearchParams) {
  return requestClient.post<PageResult<BookmarkEntity>>('/admin/bookmark/all', params);
}

/**
 * 修改书签编辑设置（内边距、背景色、是否高清、AppName），单一保存端点
 */
export async function updateBookmarkIconApi(
  bookmarkId: string,
  data: {
    appName?: null | string;
    iconBgColor?: null | string;
    iconPadding: number;
    useHdLogo: boolean;
  },
) {
  return requestClient.post<void>(`/admin/bookmark/${bookmarkId}/icon`, data);
}

/** 重新获取预览结果：重新解析得到的网站标题、小图标与高清 LOGO（不落库） */
export interface BookmarkRefetchResult {
  iconBase64?: string;
  /** scrapper 新解析的高清 LOGO 地址，未抓到为空 */
  logoUrl?: string;
  title?: string;
}

/**
 * 重新获取：重新解析网站标题与图标（不落库），返回预览数据供对比选择
 */
export async function refetchBookmarkApi(bookmarkId: string) {
  return requestClient.post<BookmarkRefetchResult>(
    `/admin/bookmark/${bookmarkId}/refetch`,
  );
}

/**
 * 应用重新获取的结果：按选择采用新标题 / 新图标并持久化，返回更新后的书签
 */
export async function applyRefetchBookmarkApi(
  bookmarkId: string,
  data: { useNewIcon: boolean; useNewLogo: boolean; useNewTitle: boolean },
) {
  return requestClient.post<BookmarkEntity>(
    `/admin/bookmark/${bookmarkId}/refetch/apply`,
    data,
  );
}

/** 手动覆盖式设置某书签的分类，返回更新后的分类列表 */
export async function updateBookmarkCategoriesApi(
  bookmarkId: string,
  categoryIds: string[],
) {
  return requestClient.post<CategoryVO[]>(
    `/admin/bookmark/${bookmarkId}/categories`,
    { categoryIds },
  );
}

/** 对某书签重新执行 DeepSeek 自动归类，返回更新后的分类列表 */
export async function recategorizeBookmarkApi(bookmarkId: string) {
  return requestClient.post<CategoryVO[]>(
    `/admin/bookmark/${bookmarkId}/categorize`,
  );
}

/** AI 推荐相似网站（仅展示，不入库；返回项带 exists 标记本地是否已收录） */
export async function findSimilarSitesApi(bookmarkId: string) {
  return requestClient.post<SimilarSite[]>(
    `/admin/bookmark/${bookmarkId}/similar`,
  );
}

/** 一键收录：异步收录传入的相似网站域名，立即返回；进度经 WebSocket 推送 */
export async function ingestSimilarSitesApi(
  bookmarkId: string,
  domains: string[],
) {
  return requestClient.post<{ count: number; started: boolean }>(
    `/admin/bookmark/${bookmarkId}/similar/ingest`,
    { domains },
  );
}

/** DeepSeek 生成书签简称建议（不落库） */
export async function generateAppNameApi(bookmarkId: string) {
  return requestClient.post<{ appName?: string }>(
    `/admin/bookmark/${bookmarkId}/appname/generate`,
  );
}
