import http from '../http/http';
import type * as types from './typing';

export const queryUserInfo = () => http.get("/user/info") as Promise<types.UserInfoShow>;
export const updateUserInfo = (param: types.UserInfoUpdate) => http.post("/user/updateInfo", param) as Promise<boolean>;
export const accountDelete = (pwd: string) => http.post("/user/del", { password: btoa(pwd) }) as Promise<boolean>;
