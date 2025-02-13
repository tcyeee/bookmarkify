import { defineStore } from 'pinia'
import type { LoginByDeviceParams, UserStore } from '~/server/apis'
import { authByDeviceInfo } from '~/server/apis'
import { nanoid } from 'nanoid'

export const StoreUser = defineStore('user', {
  persist: true,
  state: () => ({
    count: 12,
    loginStatus: false,  // 是否处于登陆状态
    auth: {
      fingerprint: '',
      deviceUid: '',
      googleId: '',
      token: '',
    }
  }),
  getters: {
  },
  actions: {
    async loginByDeviceUid() {
      if (this.loginStatus) return

      const params: LoginByDeviceParams = this.auth
      await authByDeviceInfo(params).then((res: UserStore) => {
        this.loginStatus = true
        this.auth.token = res.token
        return Promise.resolve(res)
      }).catch((err: any) => {
        ElNotification.error('会话注册失败')
      })
    },

    logout() {
      this.loginStatus = false
      this.auth = {
        fingerprint: '',
        deviceUid: '',
        googleId: '',
        token: '',
      }
    },

    /**
     * 1.fingerprint id 实时更新
     * 2.client id 没有才添加
     */
    updateFingerprint(fingerprintId: string) {
      if (this.auth.deviceUid == '') this.auth.deviceUid = nanoid()
      this.auth.fingerprint = fingerprintId
    }
  }
})