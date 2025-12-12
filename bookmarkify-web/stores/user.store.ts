import { defineStore } from 'pinia'
import { AuthStatusEnum, type UserInfo } from '@typing'
import { track, queryUserInfo } from '@api'

export const useUserStore = defineStore(
  'user',
  () => {
    /* 用户信息 */
    const account = ref<UserInfo>()
    /* 加载状态 */
    const loading = ref<Boolean>(false)
    /* 帐户状态：NONE、LOGGED、AUTHED */
    const authStatus: Ref<AuthStatusEnum> = computed(() => {
      if (account.value == undefined) return AuthStatusEnum.NONE
      const hasAuth = account.value.verified != undefined && account.value.verified == true
      return hasAuth ? AuthStatusEnum.AUTHED : AuthStatusEnum.LOGGED
    })

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
      return user
    }

    async function logout() {
      console.log('DEBUG: 退出登陆')
      account.value = undefined
    }
    return { loginOrRegister, logout, account, refreshUserInfo, authStatus }
  },
  { persist: true }
)
