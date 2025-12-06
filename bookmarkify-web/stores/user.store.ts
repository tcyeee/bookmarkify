import { defineStore } from 'pinia'
import { authByDeviceInfo } from '@api/auth'
import type { UserAuthParams, UserEntity } from '@api/auth/typing'
import { nanoid } from 'nanoid'
import { AuthStatus } from '../domain'

export const useUserStore = defineStore('user', () => {
  const authStatus = ref<AuthStatus>(AuthStatus.NotLogin);
  const auth = reactive<UserAuthParams>({
    deviceUid: queryDeviceUid()
  });

  const Loading = ref<Boolean>(false);
  async function loginByDeviceUid(): Promise<UserEntity> {
    if (Loading.value) return Promise.reject('loading..');
    Loading.value = true;
    const res = await authByDeviceInfo(auth).finally(() => { Loading.value = false })
    console.log(`[DEBUG] 重新登陆获取TOKEN:${res.token}`);
    authStatus.value = AuthStatus.Login;
    auth.token = res.token;

    // 重连 websocket
    const socketStore = useWebSocketStore()
    socketStore.connect(res.token)
    return Promise.resolve(res);
  }

  function logout() {
    authStatus.value = AuthStatus.NotLogin;
    Object.assign(auth, {})
  }

  function queryDeviceUid(): string {
    if (!import.meta.client) return ''

    var deviceUid = localStorage.getItem("deviceUid")
    if (deviceUid !== null) return deviceUid

    deviceUid = nanoid()
    localStorage.setItem("deviceUid", deviceUid)
    return deviceUid
  }
  return { auth, loginByDeviceUid, logout, authStatus };
}, { persist: true });
