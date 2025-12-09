import { defineStore } from 'pinia'
import { type UserInfoEntity } from '@typing'
import { track, queryUserInfo } from '@api'

export const useUserStore = defineStore(
  'user',
  () => {
    /* 用户信息 */
    const account = ref<UserInfoEntity>()
    /* 加载状态 */
    const loading = ref<Boolean>(false)
    /* 设备 ID 存储（cookie 可在 SSR/CSR 共用） */
    const deviceIdCookie = useCookie<string | null>('deviceUid', { sameSite: 'lax' })

    /**
     * 刷新存储中的用户信息
     */
    async function refreshUserInfo(): Promise<UserInfoEntity> {
      loading.value = true
      try {
        const result = await queryUserInfo()
        account.value = result
        return result
      } catch (err: any) {
        ElMessage.error(err.message || '刷新用户信息失败')
        if (err.code == 202) logout()
        throw err
      } finally {
        loading.value = false
      }
    }

    /**
     * 登录或注册,每次请求均会刷新用户信息
     *
     * @returns 用户信息
     */
    async function loginOrRegister(): Promise<UserInfoEntity> {
      console.log('DEBUG: 登录或者注册，刷新最新用户信息')
      loading.value = true
      const user = await track()
      loading.value = false
      account.value = user
      return user
    }

    function logout() {
      console.log('DEBUG: logout')
      account.value = undefined
      deviceIdCookie.value = null
    }
    return { login: loginOrRegister, logout, account, refreshUserInfo }
  },
  { persist: true }
)
