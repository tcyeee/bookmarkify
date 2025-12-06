import { defineStore } from 'pinia'
import { authByDeviceInfo } from '@api/auth'
import { nanoid } from 'nanoid'
import { AuthStatus } from '../domain'
import { queryUserInfo } from '@api'
import type { UserInfoEntity } from '@api/typing'

export const useUserStore = defineStore('user', () => {
  const account = ref<UserInfoEntity>()
  const Loading = ref<Boolean>(false);

  /**
   * 自动跟随 account 状态变化的认证状态
   * 如果account没有任何信息，则返回 NotLogin
   * 如果account有token，则返回 Login
   * 如果account有mail，则返回 Auth
   */
  const authStatus = computed<AuthStatus>(() => {
    if (!account.value) return AuthStatus.NotLogin;
    if (account.value.token) return AuthStatus.Login;
    if (account.value.email) return AuthStatus.Auth;
    return AuthStatus.NotLogin;
  });


  /**
   * 获取用户信息（同时刷新存储中的用户信息）
   */
  async function getUserInfo(): Promise<UserInfoEntity> {
    Loading.value = true;
    await queryUserInfo()
      .then((res: UserInfoEntity) => {
        account.value = res
        return Promise.resolve(res);
      })
      .catch((err) => {
        if (err.code == 202) logout()
        return Promise.reject(err);
      })
      .finally(() => { Loading.value = false })

    return Promise.reject(new Error('Unexpected auth status'));
  }


  /**
   * 登录方法
   * 1.检查是否已经登录，如果已经被登录，则直接返回
   * 2.如果没有登录，那么先找到临时帐户（使用DeviceID生成）
   */
  async function login(): Promise<UserInfoEntity> {
    console.log("DEBUG: login");
    if (authStatus.value === AuthStatus.Login) return account.value as UserInfoEntity;
    if (authStatus.value === AuthStatus.NotLogin) {
      const deviceId = getDeviceUid();

      // 使用DeviceID临时登录
      Loading.value = true;
      try {
        const res = await authByDeviceInfo(deviceId);
        account.value = res;
        return res;
      } catch (err) {
        return Promise.reject(err);
      } finally {
        Loading.value = false;
      }
    }
    return Promise.reject(new Error('Unexpected auth status'));
  }


  // async function loginByDeviceUid(): Promise<UserEntity> {
  //   if (Loading.value) return Promise.reject('loading..');
  //   Loading.value = true;
  //   console.log(`[DEBUG] 重新登陆获取TOKEN:${res.token}`);
  //   authStatus.value = AuthStatus.Login;
  //   auth.token = res.token;

  //   // 重连 websocket
  //   const socketStore = useWebSocketStore()
  //   socketStore.connect(res.token)
  //   return Promise.resolve(res);
  // }

  function logout() {
    console.log("DEBUG: logout");
    account.value = undefined;
    localStorage.removeItem("deviceUid")
  }

  function getDeviceUid(): string {
    if (!import.meta.client) return ''

    var deviceUid = localStorage.getItem("deviceUid")
    if (deviceUid !== null) return deviceUid

    deviceUid = nanoid()
    localStorage.setItem("deviceUid", deviceUid)
    return deviceUid
  }
  return { login, logout, authStatus, account, getUserInfo };
}, { persist: true });
