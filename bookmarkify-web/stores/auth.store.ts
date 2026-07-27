import { defineStore } from 'pinia'
import { AuthStatusEnum, type EmailVerifyParams, type LoginParams, type UserInfo } from '@typing'
import {
  authLoginByAccount,
  authLoginByGithub,
  authLoginByGoogle,
  authLogout,
  authQuickLogin,
  captchaVerifyEmail,
  queryUserInfo,
  uploadAvatar,
} from '@api'
import { generateDefaultAvatarFile, md5 } from '@utils'
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

    async loginWithGoogle(idToken: string): Promise<UserInfo> {
      try {
        // Google ID Token 登录/注册，成功后合并到当前账号信息
        const result = await authLoginByGoogle({ idToken })
        this.account = { ...this.account, ...result }
        if (import.meta.client) useNuxtApp().$track('login-google')
        return result
      } catch (err: any) {
        return Promise.reject(err)
      }
    },

    // 测试环境快捷登录：免密码登录固定测试账号，仅本地环境后端会响应（生产环境后端会拒绝）
    async loginWithQuickLogin(): Promise<UserInfo> {
      try {
        const result = await authQuickLogin()
        this.account = { ...this.account, ...result }
        if (import.meta.client) useNuxtApp().$track('login-quick')
        return result
      } catch (err: any) {
        return Promise.reject(err)
      }
    },

    async loginWithGithub(code: string, redirectUri: string): Promise<UserInfo> {
      try {
        // GitHub 授权码登录/注册，成功后合并到当前账号信息
        const result = await authLoginByGithub({ code, redirectUri })
        this.account = { ...this.account, ...result }
        if (import.meta.client) useNuxtApp().$track('login-github')
        return result
      } catch (err: any) {
        return Promise.reject(err)
      }
    },

    async refreshUserInfo(): Promise<UserInfo> {
      try {
        // 重新拉取用户信息，失败后(如 202 过期)登出并跳转 /welcome，不会自动重新登录
        const result = await queryUserInfo()

        this.account = { ...this.account, ...result }
        return result
      } catch (err: any) {
        if (err.code == 202) {
          await this.logout()
          if (import.meta.client) useToastStore().error('登录已过期,请重新登录')
          throw err
        }
        if (import.meta.client) useToastStore().error(err.message || '刷新用户信息失败')
        throw err
      }
    },

    async ensureDefaultAvatar() {
      // 仅客户端执行（依赖 File / fetch）
      if (!import.meta.client) return
      const account = this.account
      if (!account?.uid) return
      // 已有头像则不覆盖，仅在为空时生成
      if (account.avatarUrl) return
      try {
        const file = generateDefaultAvatarFile(account.uid)
        await uploadAvatar(file)
        // 重新拉取以获取带签名的头像 url 并同步到 store
        await this.refreshUserInfo()
      } catch (err) {
        // 失败静默降级，不阻塞登录；下次进入仍会因 avatarUrl 为空而重试
        console.error('[AVATAR] ensureDefaultAvatar failed', err)
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
      // 无头像时自动生成并上传默认头像（自愈历史无头像用户）
      await this.ensureDefaultAvatar()
    },

    // skipServerLogout：账号注销等场景下服务端会话已销毁，无需再请求 /auth/logout，
    // 否则会触发 101 让 http 层递归调用本方法，仅需执行本地清理与跳转即可。
    async logout(skipServerLogout = false) {
      console.log('DEBUG: 退出登陆')
      const webSocketStore = useWebSocketStore()
      const bookmarkStore = useBookmarkStore()
      const sysStore = useSysStore()
      const preferenceStore = usePreferenceStore()

      try {
        // 本地无登录信息 / 已显式跳过时，不请求后端，直接走 finally 清理
        if (skipServerLogout || this.authStatus === AuthStatusEnum.NONE) return
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
