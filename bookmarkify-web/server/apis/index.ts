import http from './http/http';
import type * as t from './typing';

export const bookmarksShowAll = () => http.post("/bookmark/query") as Promise<Array<t.HomeItem>>;
export const bookmarksAddOne = (params: t.bookmarksAddOneParams) => http.post("/bookmark/addOne", params) as Promise<t.HomeItem>;
export const bookmarksSort = (params: Array<t.BookmarkSortParams>) => http.post("/bookmark/sort", params) as Promise<boolean>;
export const bookmarksDel = (params: Array<string>) => http.post("/bookmark/delete", params) as Promise<boolean>;
export const bookmarksUpdate = (params: t.BookmarkUpdatePrams) => http.post("/bookmark/update", params) as Promise<t.Bookmark>;

export const updateUserInfo = (param: t.UserInfoUpdate) => http.post("/user/updateInfo", param) as Promise<boolean>;
export const queryUserInfo = () => http.get("/user/info") as Promise<t.UserInfoShow>;
export const accountDelete = (pwd: string) => http.post("/user/del", { password: btoa(pwd) }) as Promise<boolean>;
