import http from '../http/http';
import type * as types from './typing';

export const queryUserInfo = () => http.get("/user/info") as Promise<types.UserInfoShow>;
