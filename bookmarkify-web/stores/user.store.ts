import { defineStore } from 'pinia'
import { AuthStatus, type UserInfoEntity } from '@typing'
import { authByDeviceInfo, queryUserInfo } from '@api'
import { nanoid } from 'nanoid'

export const useUserStore = defineStore(
  'user',
  () => {
    /* 用户信息 */
    const account = ref<UserInfoEntity>()
    /* 加载状态 */
    const Loading = ref<Boolean>(false)

    /**
     * 自动跟随 account 状态变化的认证状态
     * 如果account没有任何信息，则返回 NotLogin
     * 如果account有token，则返回 Login
     * 如果account有mail，则返回 Auth
     */
    const authStatus = computed<AuthStatus>(() => {
      if (!account.value) return AuthStatus.NotLogin
      if (account.value.token) return AuthStatus.Login
      if (account.value.email) return AuthStatus.Auth
      return AuthStatus.NotLogin
    })

    /**
     * 刷新存储中的用户信息
     */
    async function refreshUserInfo(): Promise<UserInfoEntity> {
      Loading.value = true
      try {
        const result = await queryUserInfo()
        account.value = result
        return result
      } catch (err: any) {
        ElMessage.error(err.message || '刷新用户信息失败')
        if (err.code == 202) logout()
        throw err
      } finally {
        Loading.value = false
      }
    }

    /**
     * 登录方法
     * 1.检查是否已经登录，如果已经被登录，则直接返回
     * 2.如果没有登录，那么先找到临时帐户（使用DeviceID生成）
     */
    async function login(): Promise<UserInfoEntity> {
      console.log('DEBUG: login')
      if (authStatus.value != AuthStatus.NotLogin) return account.value!

      /* 即没有登录，也没有认证，则进行临时登录 */
      Loading.value = true
      try {
        const deviceId = getDeviceUid()
        const res = await authByDeviceInfo(deviceId)
        account.value = res
        return res
      } finally {
        Loading.value = false
      }
    }

    const deviceIdKey = 'deviceUid'
    function logout() {
      console.log('DEBUG: logout')
      account.value = undefined
      localStorage.removeItem(deviceIdKey)
    }

    function getDeviceUid(): string {
      var deviceUid = localStorage.getItem(deviceIdKey)
      if (deviceUid !== null) return deviceUid

      deviceUid = nanoid()
      localStorage.setItem('deviceUid', deviceUid)
      return deviceUid
    }
    return { login, logout, authStatus, account, refreshUserInfo }
  },
  { persist: true }
)
