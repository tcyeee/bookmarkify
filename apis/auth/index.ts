import http from '../http/http';
import type { LoginParams, UserStore } from './typing';

export * from './typing'


// 登录
export const authByFingerprint = (fingerPrint: string) => http.get(`/auth/init?fp=${fingerPrint}`) as Promise<UserStore>;

export const authLogin = (params: LoginParams) => http.post('/auth/login', params) as Promise<UserStore>;

export const authRegister = (params: LoginParams) => http.post('/auth/register', params) as Promise<UserStore>;

// login out
export const authLogout = () => http.post('/users/app/logout') as Promise<UserStore>;

export const authGetUserInfo = (params: LoginParams) => http.post('/users/app/getUserInfo', params) as Promise<UserStore>;