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
  HOME_ITEM_UPDATE = 'HOME_ITEM_UPDATE',
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

export enum BookmarkLayoutMode {
  COMPACT = 'COMPACT',
  DEFAULT = 'DEFAULT',
  SPACIOUS = 'SPACIOUS',
}

export enum PageTurnMode {
  VERTICAL_SCROLL = 'VERTICAL_SCROLL',
  HORIZONTAL_PAGE = 'HORIZONTAL_PAGE',
}
