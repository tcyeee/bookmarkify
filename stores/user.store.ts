import { defineStore } from 'pinia'
import type { LoginByDeviceParams, UserStore } from '~/server/apis'
import { authByDeviceId } from '~/server/apis'
import { nanoid } from 'nanoid'

export const StoreUser = defineStore('user', {
  persist: true,
  state: () => ({
    count: 12,
    fingerprint: '',
    deviceUid: '',
    token: '',
    isLoggedIn: false,
    profile: {
      token: ''
    }
  }),
  getters: {
    getFingerprint(): string {
      if (this.fingerprint == '') this.fingerprint = nanoid();
      return this.fingerprint;
    }
  },
  actions: {
    async loginByDeviceUid() {
      if (this.isLoggedIn) return

      const params: LoginByDeviceParams = {
        fingerprint: this.fingerprint,
        deviceUid: this.deviceUid
      }
      await authByDeviceId(params).then((res: UserStore) => {
        this.isLoggedIn = true
        this.token = res.token
        return Promise.resolve(res)
      }).catch((err: any) => {
        ElNotification.error('会话注册失败')
      })
    },

    logout() {
      this.isLoggedIn = false
      ElNotification.success('注销成功!');
    },

    /**
     * 1.fingerprint id 实时更新
     * 2.client id 没有才添加
     */
    updateFingerprint(fingerprintId: string) {
      if (this.deviceUid == '') this.deviceUid = nanoid()
      this.fingerprint = fingerprintId
    }
  }
})