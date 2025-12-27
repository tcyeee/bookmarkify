import { defineStore } from 'pinia'
import {
  AuthStatusEnum,
  type EmailVerifyParams,
  type SmsVerifyParams,
  type UserFile,
  type UserInfo,
  type UserSetting,
} from '@typing'
import { track, queryUserInfo, authLogout, captchaVerifyEmail, captchaVerifySms } from '@api'

export const useUserStore = defineStore(
  'user',
  () => {
    /* 用户账户信息 */
    const account = ref<UserInfo>()
    /* 用户设置信息 */
    const setting = ref<UserSetting>()
    /* 用户头像 */
    const avatar = ref<UserFile>()
    /* 加载状态 */
    const loading = ref<Boolean>(false)
    /* 帐户状态：NONE、LOGGED、AUTHED */
    const authStatus: Ref<AuthStatusEnum> = computed(() => {
      if (account.value == undefined) return AuthStatusEnum.NONE
      const hasAuth = account.value.verified != undefined && account.value.verified == true
      return hasAuth ? AuthStatusEnum.AUTHED : AuthStatusEnum.LOGGED
    })

    /**
     * 登录用户（邮箱+验证码）
     */
    async function loginWithEmail(params: EmailVerifyParams): Promise<UserInfo> {
      loading.value = true
      try {
        const result = await captchaVerifyEmail(params)
        account.value = { ...account.value, ...result }
        return result
      } catch (err: any) {
        return Promise.reject(err)
      } finally {
        loading.value = false
      }
    }

    /**
     * 登录用户（手机号+验证码）
     */
    async function loginWithPhone(params: SmsVerifyParams): Promise<UserInfo> {
      loading.value = true
      try {
        const result = await captchaVerifySms(params)
        account.value = { ...account.value, ...result }
        authStatus.value = AuthStatusEnum.AUTHED
        return result
      } catch (err: any) {
        return Promise.reject(err)
      } finally {
        loading.value = false
      }
    }

    /**
     * 获取用户信息（包含头像和设置信息）
     */
    async function refreshUserInfo(): Promise<UserInfo> {
      loading.value = true
      try {
        const result = await queryUserInfo()
        account.value = { ...account.value, ...result }
        return result
      } catch (err: any) {
        if (err.code == 202) {
          await logout()
          ElMessage.error('用户信息已过期,已重新登录,请刷新页面')
          return await loginOrRegister()
        }
        ElMessage.error(err.message || '刷新用户信息失败')
        throw err
      } finally {
        loading.value = false
      }
    }

    /**
     * 登录或注册,每次请求均会刷新用户信息,仅在引导页允许注册
     *
     * @returns 用户信息+TOKEN（不含头像和设置信息）
     */
    async function loginOrRegister(): Promise<UserInfo> {
      console.log('DEBUG: 登录或者注册，刷新最新用户信息')
      loading.value = true
      const user = await track()
      loading.value = false
      account.value = { ...account.value, ...user }
      if (!user.token) return Promise.reject('登陆数据异常')

      // 更新书签
      const bookmarkStore = useBookmarkStore()
      bookmarkStore.update()

      // 连接websocket
      const webSocketStore = useWebSocketStore()
      webSocketStore.connect(user.token)

      // 更新用户信息
      // refreshUserInfo()
      return Promise.resolve(account.value)
    }

    async function logout() {
      console.log('DEBUG: 退出登陆')
      await authLogout()
      const webSocketStore = useWebSocketStore()
      webSocketStore.disconnect()
      account.value = undefined

      // 清理书签缓存
      const bookmarkStore = useBookmarkStore()
      bookmarkStore.$reset()

      // 清理user缓存
      account.value = undefined
      setting.value = undefined
      avatar.value = undefined
      loading.value = false

      navigateTo('/welcome')
    }

    return { loginOrRegister, logout, account, refreshUserInfo, authStatus, loginWithEmail, loginWithPhone }
  },
  {
    persist: true,
  }
)
