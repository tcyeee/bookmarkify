export enum Environments {
  DEV = 'development',
  PRO = 'production',
}

export enum AuthStatus {
  NotLogin = 'NotLogin',
  Login = 'Login',
  Auth = 'Auth',
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
  AVATAR_IMAGE = 'AVATAR_IMAGE',
  /* 背景图片 */
  BACKGROUND_IMAGE = 'BACKGROUND_IMAGE',
  /* 其他文件 */
  OTHER_FILE = 'OTHER_FILE',
}

export enum HomeItemType {
  BOOKMARK = 'BOOKMARK',
  BOOKMARK_DIR = 'BOOKMARK_DIR',
  SETTING = 'SETTING',
  LOADING = 'LOADING',
}
