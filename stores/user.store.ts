import { defineStore } from 'pinia'
import { authByDeviceInfo, type UserAuth, type UserEntity } from '~/server/apis'
import { nanoid } from 'nanoid'

export const useUserStore = defineStore('user', () => {
  const loggedIn = ref<Boolean>(false);
  const auth = reactive<UserAuth>({});
  const Loading = ref<Boolean>(false);

  async function loginByDeviceUid(): Promise<UserEntity> {
    if (Loading.value) return Promise.reject('loading..');
    Loading.value = true;
    const res = await authByDeviceInfo(auth).finally(() => { Loading.value = false })
    console.log(`[DEBUG] 重新登陆获取TOKEN:${res.token}`);
    loggedIn.value = true;
    auth.token = res.token;

    // 重连 websocket
    const socketStore = useWebSocketStore()
    socketStore.connect(res.token)
    return Promise.resolve(res);
  }

  function logout() {
    loggedIn.value = false
    Object.assign(auth, {})
  }

  function updateFingerprint(fingerprintId: string) {
    if (!auth.deviceUid) auth.deviceUid = nanoid();
    auth.fingerprint = fingerprintId;
  }

  return { auth, loginByDeviceUid, logout, updateFingerprint };
}, { persist: true });
