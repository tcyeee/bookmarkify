import http from '../http/http';
import * as type from './typing';
export * from './typing'

export const authByDeviceInfo = (params: type.LoginByDeviceParams) => http.post('/client/login', params) as Promise<type.UserStore>;

export const authLogin = (params: type.LoginParams) => http.post('/auth/login', params) as Promise<type.UserStore>;
export const authRegister = (params: type.LoginParams) => http.post('/auth/register', params) as Promise<type.UserStore>;
export const authLogout = () => http.post('/users/app/logout') as Promise<type.UserStore>;
export const authGetUserInfo = (params: type.LoginParams) => http.post('/users/app/getUserInfo', params) as Promise<type.UserStore>;