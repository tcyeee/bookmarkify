import type { UserInfo } from '@vben/types';

import { requestClient } from '#/api/request';

/**
 * 获取用户信息
 */
export async function getUserInfoApi() {
  return requestClient.get<UserInfo>('/admin/info');
}

/**
 * 获取当前管理员头像签名 URL（有效期较短，按需调用，请勿持久化）
 */
export async function getAvatarUrlApi() {
  return requestClient.get<null | string>('/admin/avatar-url');
}

/**
 * 修改昵称
 */
export async function updateUserInfoApi(nickName: string) {
  return requestClient.post<boolean>('/admin/info', { nickName });
}

/**
 * 上传头像
 */
export async function uploadAvatarApi(file: File) {
  return requestClient.upload<string>('/admin/avatar', { file });
}
