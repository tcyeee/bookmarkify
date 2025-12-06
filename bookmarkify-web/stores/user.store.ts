import { defineStore } from 'pinia'
import { authByDeviceInfo } from '@api/auth'
import type { UserEntity } from '@api/auth/typing'
import { nanoid } from 'nanoid'
import { AuthStatus } from '../domain'

export const useUserStore = defineStore('user', () => {
  const account = ref<UserEntity>()
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
    if (account.value.mail) return AuthStatus.Auth;
    return AuthStatus.NotLogin;
  });

  /**
   * 登录方法
   * 1.检查是否已经登录，如果已经被登录，则直接返回
   * 2.如果没有登录，那么先找到临时帐户（使用DeviceID生成）
   */
  async function login() {
    console.log("=======开始检查用户登录状态=======");
    if (authStatus.value === AuthStatus.Login) return

    if (authStatus.value === AuthStatus.NotLogin) {
      const deviceId = getDeviceUid();

      // 使用DeviceID临时登录
      Loading.value = true;
      authByDeviceInfo(deviceId)
        .then((res) => {
          console.log("---");
          account.value = res;
          console.log(account.value);
          console.log("---");
          return Promise.resolve(res);
        })
        .finally(() => { Loading.value = false })
        .catch((err) => {
          return Promise.reject(err);
        });
    }
  }


  // async function loginByDeviceUid(): Promise<UserEntity> {
  //   if (Loading.value) return Promise.reject('loading..');
  //   Loading.value = true;
  //   const res = await authByDeviceInfo(auth).finally(() => { Loading.value = false })
  //   console.log(`[DEBUG] 重新登陆获取TOKEN:${res.token}`);
  //   authStatus.value = AuthStatus.Login;
  //   auth.token = res.token;

  //   // 重连 websocket
  //   const socketStore = useWebSocketStore()
  //   socketStore.connect(res.token)
  //   return Promise.resolve(res);
  // }

  function logout() {
    account.value = undefined;
  }

  function getDeviceUid(): string {
    if (!import.meta.client) return ''

    var deviceUid = localStorage.getItem("deviceUid")
    if (deviceUid !== null) return deviceUid

    deviceUid = nanoid()
    localStorage.setItem("deviceUid", deviceUid)
    return deviceUid
  }
  return { login, logout, authStatus, account };
}, { persist: true });
