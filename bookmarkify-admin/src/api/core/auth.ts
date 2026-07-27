import { baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  /** 登录接口参数 */
  export interface LoginParams {
    password?: string;
    account?: string;
  }

  /** 登录接口返回值 */
  export interface LoginResult {
    tokenValue: string;
  }
}

/**
 * 登录
 */
export async function loginApi(data: AuthApi.LoginParams) {
  return requestClient.post<AuthApi.LoginResult>('/admin/login', data);
}

/**
 * 退出登录
 */
export async function logoutApi() {
  return baseRequestClient.post('/admin/logout', {
    withCredentials: true,
  });
}
