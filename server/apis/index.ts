import http from './http/http';
import type * as t from './typing';

export * from './sys'
export * from './auth'
export * from './bookmark'
export * from './user'


export const updateUserInfo = (param: t.UserInfoUpdate) => http.post("/user/updateInfo", param) as Promise<boolean>;
export const queryUserInfo = () => http.get("/user/info") as Promise<t.UserInfoShow>;
export const accountDelete = (pwd: string) => http.post("/user/del", { password: btoa(pwd) }) as Promise<boolean>;
