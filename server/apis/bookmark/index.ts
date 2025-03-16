import http from '../http/http';
import type * as types from './typing';

export const bookmarksShowAll = () => http.post("/bookmark/query") as Promise<Array<types.HomeItem>>;
export const bookmarksAddOne = (params: types.bookmarksAddOneParams) => http.post("/bookmark/addOne", params) as Promise<types.HomeItem>;
export const bookmarksSort = (params: Array<types.BookmarkSortParams>) => http.post("/bookmark/sort", params) as Promise<boolean>;
export const bookmarksDel = (params: Array<string>) => http.post("/bookmark/delete", params) as Promise<boolean>;
export const bookmarksUpdate = (params: types.BookmarkUpdatePrams) => http.post("/bookmark/update", params) as Promise<types.Bookmark>;
