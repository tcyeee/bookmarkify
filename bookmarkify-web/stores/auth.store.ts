import { defineStore } from 'pinia'
import { AuthStatusEnum, type EmailVerifyParams, type LoginParams, type UserInfo } from '@typing'
import { authLoginByAccount, authLogout, captchaVerifyEmail, queryUserInfo } from '@api'
import { md5 } from '@utils'
import { usePreferenceStore } from './preference.store'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // 当前登录账号信息（包含 token/验证状态等）
    account: undefined as UserInfo | undefined,
  }),

  getters: {
    authStatus(state): AuthStatusEnum {
      if (state.account == undefined) return AuthStatusEnum.NONE
      const hasAuth = state.account.verified != undefined && state.account.verified == true
      return hasAuth ? AuthStatusEnum.AUTHED : AuthStatusEnum.LOGGED
    },
  },

  actions: {
    async loginWithEmail(params: EmailVerifyParams): Promise<UserInfo> {
      try {
        // 邮箱验证码登录/注册，成功后合并到当前账号信息
        const result = await captchaVerifyEmail(params)
        this.account = { ...this.account, ...result }
        if (import.meta.client) useNuxtApp().$track('login-email')
        return result
      } catch (err: any) {
        return Promise.reject(err)
      }
    },

    async loginWithPassword(params: LoginParams): Promise<UserInfo> {
      try {
        const result = await authLoginByAccount({
          account: btoa(params.account),
          password: btoa(md5(params.password)),
        })
        this.account = { ...this.account, ...result }
        if (import.meta.client) useNuxtApp().$track('login-password')
        return result
      } catch (err: any) {
        return Promise.reject(err)
      }
    },

    async refreshUserInfo(): Promise<UserInfo> {
      try {
        // 重新拉取用户信息，失败后处理过期态并自动重新登录
        const result = await queryUserInfo()

        this.account = { ...this.account, ...result }

        // 同步用户头像（仅客户端，服务端无 Pinia 活跃上下文）
        if (import.meta.client) {
          const preferenceStore = usePreferenceStore()
          preferenceStore.refreshAvatar()
        }
        return result
      } catch (err: any) {
        if (err.code == 202) {
          await this.logout()
          if (import.meta.client) ElMessage.error('登录已过期,请重新登录')
          throw err
        }
        if (import.meta.client) ElMessage.error(err.message || '刷新用户信息失败')
        throw err
      }
    },

    async postLoginSetup() {
      if (!this.account?.token) return

      // 建立实时通道
      const webSocketStore = useWebSocketStore()
      webSocketStore.connect(this.account.token)

      // 并行拉取：用户基础信息、偏好设置、书签
      const preferenceStore = usePreferenceStore()
      const bookmarkStore = useBookmarkStore()
      await Promise.all([this.refreshUserInfo(), preferenceStore.fetchPreference(), bookmarkStore.update()])
    },

    async logout() {
      console.log('DEBUG: 退出登陆')
      const webSocketStore = useWebSocketStore()
      const bookmarkStore = useBookmarkStore()
      const sysStore = useSysStore()
      const preferenceStore = usePreferenceStore()

      try {
        // 如果本地不存在登录信息，那么就不要请求后端了
        if (this.authStatus === AuthStatusEnum.NONE) return
        // 服务端登出失败也要继续清理本地态
        await authLogout()
      } catch (err) {
        console.error('authLogout failed, continue cleanup', err)
      } finally {
        // 关闭实时通道并重置各业务 store
        webSocketStore.disconnect()
        bookmarkStore.$reset()
        sysStore.$reset()
        preferenceStore.clearBackgroundImageCache()
        preferenceStore.$reset()
        this.$reset()

        if (import.meta.client) {
          // 清理前端缓存与 cookie，避免残留
          localStorage.removeItem('homeItems')
          localStorage.removeItem('user')
          localStorage.removeItem('backgroundImageDataUrl')
          document.cookie = 'satoken=;auth=;user=; Max-Age=0; path=/'
        }

        navigateTo('/welcome')
      }
    },
  },

  persist: true,
})
