import { defineStore } from 'pinia'
import { authByDeviceInfo, type UserAuth, type UserEntity } from '~/server/apis'
import { nanoid } from 'nanoid'

export const useUserStore = defineStore('user', () => {
  const loggedIn = ref<Boolean>(false);
  const auth = reactive<UserAuth>({});

  async function loginByDeviceUid(): Promise<UserEntity> {
    const res: UserEntity = await authByDeviceInfo(auth);
    console.log(`[DEBUG] 重新登陆获取TOKEN:${res.token}`);
    loggedIn.value = true;

    auth.token = res.token;
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
