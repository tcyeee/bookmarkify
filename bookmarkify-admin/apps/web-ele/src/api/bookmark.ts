import { requestClient } from '#/api/request';

export interface BookmarkEntity {
  id: string;
  urlHost: string;
  urlPath?: string;
  urlScheme: string;
  appName?: string;
  title?: string;
  description?: string;
  iconBase64?: string;
  logoUrl?: string;
  maximalLogoSize: number;
  iconPadding: number;
  iconBgColor?: string;
  useHdLogo?: boolean;
  parseStatus: 'LOADING' | 'SUCCESS' | 'CLOSED' | 'BLOCKED'; // Add other statuses as needed
  isActivity: boolean;
  parseErrMsg?: string;
  createTime: string;
  updateTime?: string;
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

/** DeepSeek 生成书签简称建议（不落库） */
export async function generateAppNameApi(bookmarkId: string) {
  return requestClient.post<{ appName?: string }>(
    `/admin/bookmark/${bookmarkId}/appname/generate`,
  );
}
