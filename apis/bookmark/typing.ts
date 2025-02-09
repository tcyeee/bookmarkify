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
    iconUrl: string;
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
    type: string; // 书签类型,可用值:BOOKMARK,BOOKMARK_DIR,SETTING
    typeApp: Bookmark;
    typeDir: BookmarkDir;
    typeFuc: string; // 方法枚举 USER_INFO BOOKMARK_MANAGE
}

export interface BookmarkDir {
    name: string;
    bookmarkList: Array<Bookmark>;
    bookmarkUserLinkIds: Array<String>;
};
