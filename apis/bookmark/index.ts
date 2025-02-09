import http from '../http/http';
import type { HomeItem, bookmarksAddOneParams, BookmarkSortParams, Bookmark, BookmarkUpdatePrams as BookmarkUpdatePrams } from './typing';

export const bookmarksShowAll = () => http.get("/bookmark/my") as Promise<Array<HomeItem>>;
export const bookmarksAddOne = (params: bookmarksAddOneParams) => http.post("/bookmark/addOne", params) as Promise<Array<HomeItem>>;
export const bookmarksSort = (params: Array<BookmarkSortParams>) => http.put("/bookmark/sort", params) as Promise<boolean>;
export const bookmarksDel = (params: Array<BookmarkSortParams>) => http.put("/bookmark/delete", params) as Promise<boolean>;
export const bookmarksUpdate = (params: BookmarkUpdatePrams) => http.put("/bookmark/update", params) as Promise<Bookmark>;
