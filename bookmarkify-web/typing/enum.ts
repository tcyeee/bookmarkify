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
  BOOKMARK_DIR = 'BOOKMARK_DIR',
  SETTING = 'SETTING',
  LOADING = 'LOADING',
}

export enum SocketTypes {
  BOOKMARK_UPDATE_ONE = 'BOOKMARK_UPDATE_ONE',
}
