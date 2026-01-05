import { defineStore } from 'pinia'
import {
  AuthStatusEnum,
  BookmarkLayoutMode,
  BookmarkOpenMode,
  PageTurnMode,
  type EmailVerifyParams,
  type SmsVerifyParams,
  type UserFile,
  type UserInfo,
  type UserPreference,
  type BacSettingVO,
} from '@typing'
import {
  track,
  queryUserInfo,
  authLogout,
  captchaVerifyEmail,
  captchaVerifySms,
  queryUserPreference,
  updateUserPreference,
} from '@api'

function createDefaultPreference(): UserPreference {
  return {
    bookmarkOpenMode: BookmarkOpenMode.CURRENT_TAB,
    minimalMode: false,
    bookmarkLayout: BookmarkLayoutMode.DEFAULT,
    showTitle: true,
    pageMode: PageTurnMode.VERTICAL_SCROLL,
    imgBacShow: undefined,
  }
}

// 用户相关的 Pinia Store（账号信息、设置、头像、登录状态等）
export const useUserStore = defineStore('user', {
  // 存放用户相关状态
  state: () => ({
    // 用户基本信息（含 token 等）
    account: undefined as UserInfo | undefined,
    // 用户个性化设置
    preference: undefined as UserPreference | null | undefined,
    // 用户头像文件
    avatar: undefined as UserFile | undefined,
    // 通用加载状态（登录、拉取信息等异步请求时使用）
    loading: false as boolean,
  }),

  // 基于 state 派生出的计算属性
  getters: {
    // 当前账号认证状态：未登录 / 已登录未认证 / 已认证
    authStatus(state): AuthStatusEnum {
      if (state.account == undefined) return AuthStatusEnum.NONE
      const hasAuth = state.account.verified != undefined && state.account.verified == true
      return hasAuth ? AuthStatusEnum.AUTHED : AuthStatusEnum.LOGGED
    },
  },

  actions: {
    // 获取用户偏好设置，并同步到 store
    async fetchPreference(): Promise<UserPreference | null> {
      try {
        const result = await queryUserPreference()
        this.preference = result ?? null
        return this.preference
      } catch (err: any) {
        ElMessage.error(err?.message || '获取偏好设置失败')
        throw err
      }
    },

    // 仅更新偏好中的背景配置（保持其他偏好字段不变）
    upsertPreferenceBackground(setting: BacSettingVO | null | undefined) {
      const base = this.preference ?? createDefaultPreference()
      this.preference = { ...base, imgBacShow: setting ?? undefined }
    },

    // 更新用户偏好设置，并同步到 store
    async savePreference(preference: UserPreference): Promise<boolean> {
      try {
        const ok = await updateUserPreference(preference)
        if (ok) this.preference = { ...preference }
        return ok
      } catch (err: any) {
        ElMessage.error(err?.message || '保存偏好设置失败')
        throw err
      }
    },


    // 使用邮箱 + 验证码登录
    async loginWithEmail(params: EmailVerifyParams): Promise<UserInfo> {
      this.loading = true
      try {
        const result = await captchaVerifyEmail(params)
        // 用接口返回结果合并当前账号信息，避免丢失已有字段
        this.account = { ...this.account, ...result }
        return result
      } catch (err: any) {
        return Promise.reject(err)
      } finally {
        this.loading = false
      }
    },

    // 使用手机号 + 验证码登录
    async loginWithPhone(params: SmsVerifyParams): Promise<UserInfo> {
      this.loading = true
      try {
        const result = await captchaVerifySms(params)
        this.account = { ...this.account, ...result }
        return result
      } catch (err: any) {
        return Promise.reject(err)
      } finally {
        this.loading = false
      }
    },

    // 刷新用户信息（会重新从后端获取账号信息）
    async refreshUserInfo(): Promise<UserInfo> {
      this.loading = true
      try {
        const result = await queryUserInfo()
        this.account = { ...this.account, ...result }
        return result
      } catch (err: any) {
        // 202 表示用户信息/登录态过期，触发重新登录流程
        if (err.code == 202) {
          await this.logout()
          ElMessage.error('用户信息已过期,已重新登录,请刷新页面')
          return await this.loginOrRegister()
        }
        ElMessage.error(err.message || '刷新用户信息失败')
        throw err
      } finally {
        this.loading = false
      }
    },

    // 登录或注册（匿名访问时自动为用户创建账号并登录）
    async loginOrRegister(): Promise<UserInfo> {
      console.log('DEBUG: 登录或者注册，刷新最新用户信息')
      this.loading = true
      // track 会在后端创建/确认用户并返回最新的账号信息
      const user = await track()
      this.loading = false
      this.account = { ...this.account, ...user }
      if (!user.token) return Promise.reject('登陆数据异常')

      // 登录成功后刷新书签数据
      const bookmarkStore = useBookmarkStore()
      bookmarkStore.update()

      // 使用用户 token 连接 websocket
      const webSocketStore = useWebSocketStore()
      webSocketStore.connect(user.token)

      // 返回当前账号信息（此时 state 中已是最新数据）
      return Promise.resolve(this.account as UserInfo)
    },

    // 退出登录：清理所有与用户相关的状态和连接
    async logout() {
      console.log('DEBUG: 退出登陆')
      // 依次获取需要联动清理的其他 store
      const webSocketStore = useWebSocketStore()
      const bookmarkStore = useBookmarkStore()
      const sysStore = useSysStore()

      try {
        // 通知后端注销登录状态（失败也继续本地清理）
        await authLogout()
      } catch (err) {
        console.error('authLogout failed, continue cleanup', err)
      } finally {
        // 断开 websocket 连接
        webSocketStore.disconnect()
        bookmarkStore.$reset()
        sysStore.$reset()
        this.$reset()

        // 显式清除持久化存储，防止被 hydrate 再写回
        if (import.meta.client) {
          localStorage.removeItem('homeItems')
          localStorage.removeItem('user')
          document.cookie = 'user=;deviceUid=; Max-Age=0; path=/'
        }

        // 跳转回欢迎/登录引导页
        navigateTo('/welcome')
      }
    },
  },
  // 这里不使用local storage 是因为如果使用会导致每次
  // 刷新重新加载信息需要一定的时间, 导致登陆状态丢失
  persist: true,
})
