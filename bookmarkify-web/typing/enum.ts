export enum Environments {
  DEV = 'development',
  PRO = 'production',
}

export enum BackgroundType {
  GRADIENT = 'GRADIENT',
  IMAGE = 'IMAGE',
}

export enum CurrentEnvironment {
  /* 本地测试环境 */
  LOCAL = 'LOCAL',
  /* 线上发布环境 */
  PROD = 'PROD',
}

export enum UserFileType {
  /* 头像图片 */
  AVATAR = 'AVATAR',
  /* 背景图片 */
  BACKGROUND = 'BACKGROUND',
}

export enum HomeItemType {
  BOOKMARK = 'BOOKMARK',
  BOOKMARK_LOADING = 'BOOKMARK_LOADING',
  BOOKMARK_DIR = 'BOOKMARK_DIR',
  FUNCTION = 'FUNCTION',
}

export enum SocketTypes {
  /* 单个书签节点的内容更新（抓取完成、LOADING 收口）；payload 必为 type=BOOKMARK 且 typeApp 非空 */
  HOME_ITEM_UPDATE = 'HOME_ITEM_UPDATE',
  /* 单个文件夹节点及其直接子节点，用于移动/建夹后的结构同步 */
  HOME_DIR_UPDATE = 'HOME_DIR_UPDATE',
  /* 整棵桌面布局树，用于文件夹被解散/删除这类结构大变动 */
  HOME_LAYOUT_REFRESH = 'HOME_LAYOUT_REFRESH',
  SHARE_STATUS_CHANGED = 'SHARE_STATUS_CHANGED',
}

export enum AuthStatusEnum {
  /* 未登录 */
  NONE = 'NONE',
  /* 已登录 */
  LOGGED = 'LOGGED',
  /* 已认证 */
  AUTHED = 'AUTHED',
}

export enum BookmarkOpenMode {
  CURRENT_TAB = 'CURRENT_TAB',
  NEW_TAB = 'NEW_TAB',
}

export enum BookmarkGapMode {
  COMPACT = 'COMPACT',
  DEFAULT = 'DEFAULT',
  SPACIOUS = 'SPACIOUS',
}

export enum BookmarkImageSize {
  LARGE = 'LARGE',
  MEDIUM = 'MEDIUM',
  SMALL = 'SMALL',
}

export enum PageTurnMode {
  VERTICAL_SCROLL = 'VERTICAL_SCROLL',
  HORIZONTAL_PAGE = 'HORIZONTAL_PAGE',
}

export enum FunctionType { SETTING = 'SETTING' }

// ── 以下枚举由后端拥有，从生成物里转出，不要在本文件里手写 ──
//
// 本文件上面那些是**前端自己的**枚举（渲染模式、Socket 消息名、布局偏好…），后端不认识它们，
// 手写是对的。而下面这几个是接口契约的一部分：后端加一个取值，手抄的这份不会有任何提示，
// 那个取值只会在界面上静默变成空白。生成与校验见 bookmarkify-api 的 SharedEnumGenerator。
export { BookmarkLinkType, ShareStatus } from './enums.generated'
