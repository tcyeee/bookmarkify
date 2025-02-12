import { defineStore } from 'pinia'
import type { UserStore } from '~/server/apis'
import { authByFingerprint } from '~/server/apis'
import { nanoid } from 'nanoid'

export const StoreUser = defineStore('user', {
  persist: true,
  state: () => ({
    count: 12,
    fingerprint: '',
    clientUid: '',
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
    async login() {
      if (this.isLoggedIn) return
      await authByFingerprint(this.getFingerprint).then((res: UserStore) => {
        //     this.isLoggedIn = true
        //     ElNotification({
        //       title: 'Success',
        //       message: '登录成功!',
        //       type: 'success',
        return Promise.resolve(res)
      })
      //   }).catch((e: any) => {
      //     ElNotification({
      //       title: 'Error',
      //       message: '登录失败!',
      //       type: 'error',
      //     })
      //   })
    },
    logout() {
      this.isLoggedIn = false
      this.profile = { token: '' }
      ElNotification({
        title: 'Success',
        message: '注销成功!',
        type: 'success',
      })
    },

    /**
     * 1.fingerprint id 实时更新
     * 2.client id 没有才添加
     */
    updateFingerprint(fingerprintId: string) {
      if (this.clientUid == '') this.clientUid = nanoid()
      this.fingerprint = fingerprintId
    }
  }
})