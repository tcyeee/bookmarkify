export enum BackgroundType {
    GRADIENT = "GRADIENT",
    IMAGE = "IMAGE"
}

export interface GradientConfig {
    colors: string[]  // 渐变色数组，至少2个颜色
    direction?: number  // 渐变方向角度，默认135
}

export interface BackgroundConfig {
    type: BackgroundType
    gradient?: GradientConfig  // 当type为GRADIENT时使用
    imagePath?: string  // 当type为IMAGE时使用
}

export interface UserInfoEntity {
    uid: string
    token: string
    nickName: string
    phone?: string
    email?: string
    avatarPath?: string
    backgroundPath?: string  // 兼容旧版本，保留
    backgroundConfig?: BackgroundConfig  // 新的背景配置
    verified: boolean
}

export interface UserInfoUpdate {
    nickName?: string
    phone?: string
}

export interface BookmarkUpdatePrams {
    linkId: string
    title: string
    description: string
}

export interface BookmarkSortParams {
    id: string;
    sort: number
}

export interface Bookmark {
    uid: string;
    bookmarkId: string;
    bookmarkUserLinkId: string;

    isActivity: string;
    urlFull: string;
    title: string;
    description: string;

    iconActivity: boolean;
    iconHd: boolean;
    iconUrlFull: string;
}

export interface BookmarkUpdateParams {
    id: string;
    iconActivity?: boolean;
    iconHd?: boolean;
}

export interface bookmarksAddOneParams {
    url: string
}

export interface BookmarkTag {
    id: string;
    name: string;
    uid: string;
    description: string;
    color: string;
}

export interface BookmarkDetail {
    bookmark: Bookmark;
    tags: Array<BookmarkTag>;
}

export interface HomeItem {
    id: string;
    uid: string;
    sort: number;
    type: HomeItemType; // 书签类型,可用值:
    typeApp: Bookmark;
    typeDir: BookmarkDir;
    typeFuc: string; // 方法枚举 USER_INFO BOOKMARK_MANAGE
    bookmarkId?: string  // 用于新建书签时定位
}

export enum HomeItemType {
    BOOKMARK = "BOOKMARK",
    BOOKMARK_DIR = "BOOKMARK_DIR",
    SETTING = "SETTING",
    LOADING = "LOADING"
}

export interface BookmarkDir {
    name: string;
    bookmarkList: Array<Bookmark>;
    bookmarkUserLinkIds: Array<string>;
};
