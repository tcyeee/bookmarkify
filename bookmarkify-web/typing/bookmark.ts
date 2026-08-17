import type { BookmarkLinkType, FunctionType, HomeItemType } from './enum'

// 根层在归一化 order 中的固定键
export const ROOT_KEY = '__root__'

// 书签节点
export interface UserLayoutNodeVO {
  id: string
  sort: number
  type: HomeItemType
  parentId?: string | null
  name?: string | null
  typeApp?: BookmarkShow
  typeFuc?: BookmarkFunctionVO
  children?: Array<UserLayoutNodeVO>
}

export interface BookmarkFunctionVO {
  id: string,
  layoutNodeId: string,
  type: FunctionType,
}

// 书签图标：服务端按展示模式选好的单一结果（后端 BookmarkLogoShowVO）
// 挑哪张是服务端策略（AssetRolePolicy），前端只负责渲染，不再自己在多个图位间取舍
export interface BookmarkLogo {
  // 已签名并按模式缩放的地址；monogram 为 true 时为空
  url?: string
  // 这张图实际是什么
  role?: 'FAVICON' | 'LOGO' | 'SOCIAL' | 'SCREENSHOT'
  quality?: 'TRUSTED' | 'DEGRADED'
  isVector?: boolean
  // true = 该站没有够格的图，应渲染首字母色块而非硬拉伸小图
  monogram?: boolean
}

// 书签详情（后端 BookmarkShow）
export interface BookmarkShow {
  // canonical 页面记录ID（后端 page.id）。判重/「已添加」比对用它，因为 BookmarkSearchVO.id 也是这个。
  // 注意命名历史：三层重命名(3b0c102d)前它叫 bookmarkId，现在那个名字归下面那条了。
  pageId: string
  // 本用户与该页面的关联ID（后端 bookmark.id，旧表名 bookmark_user_link）。
  // 打开计数(bookmarksRecordOpen)/置顶(bookmarksPin)/封面等按“用户的这一条”操作的接口都要它。
  bookmarkId: string
  title: string
  // 磁贴形态（大图 + 一行短文案）该显示的文案，服务端 BookmarkDisplayPolicy 按 TILE 模式算好下发：
  // 首页书签取站点短名(manifest short_name)，深链仍取页面标题——同站深链的图标一模一样，
  // 文案再统一成短名就没法区分了。置顶区用它，桌面列表行仍用 title。
  tileTitle?: string
  description: string
  urlFull: string
  urlBase: string
  pinned: boolean
  // 置顶区内的顺序（越小越靠前）。与桌面树的 sort 是两套：置顶区把各文件夹里的书签平铺成一行，
  // 跨层的先后在按层记的 sort 里表达不出来。仅 pinned 为 true 时有意义。
  pinnedSort: number
  // 书签链接类型：本地/IP 类型不会被后端抓取，前端仅展示统一的 mdi 圆圈图标
  linkType: BookmarkLinkType
  // 图标相关字段统一收拢到 logo（后端 website_logo 表）
  logo: BookmarkLogo
  isActivity: boolean
  createTime?: number
  paths?: Array<string>
  // 用户桌面排布节点ID：批量删除(bookmarksDel)/创建集合(bookmarksCreateDir)等基于节点树的操作使用此ID，而非 bookmarkId
  layoutNodeId?: string | null
  folderId?: string | null
  folderName?: string | null
}

export interface BookmarkDir {
  name?: string
  bookmarkList: Array<BookmarkShow>
}

export interface BookmarkUpdateParams {
  id: string
  iconActivity?: boolean
  iconHd?: boolean
}

export interface BookmarksAddOneParams {
  url: string
}

export interface BookmarkListParams {
  name?: string
  currentPage?: number
  pageSize?: number
  duplicatesOnly?: boolean
  invalidOnly?: boolean
}

export interface BookmarkPage<T = BookmarkShow> {
  records: Array<T>
  total: number
  current: number
  size: number
}

export interface BookmarkUpdatePrams {
  linkId: string
  title: string
  description: string
}

export interface BookmarkSortParams {
  id: string
  sort: number
}

export interface BookmarkImportItemVO {
  title: string
  url: string
  folder?: string | null
  isDuplicate: boolean
}

export interface BookmarkImportPreviewVO {
  total: number
  duplicateCount: number
  items: BookmarkImportItemVO[]
}
