import http from '../http/http';
import * as type from './typing';
export * from './typing'

export const authByDeviceInfo = (params: type.UserAuthParams) => http.post('/auth/login', params) as Promise<type.UserEntity>;
export const authLogout = () => http.post('/users/app/logout') as Promise<type.UserEntity>;
export const authGetUserInfo = (params: type.LoginParams) => http.post('/users/app/getUserInfo', params) as Promise<type.UserEntity>;