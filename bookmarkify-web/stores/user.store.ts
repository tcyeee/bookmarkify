import { defineStore } from 'pinia'
import { AuthStatus, type UserInfoEntity, type UserSetting } from '@typing'
import { authByDeviceInfo, queryUserInfo } from '@api'
import { nanoid } from 'nanoid'

export const useUserStore = defineStore(
  'user',
  () => {
    const account = ref<UserInfoEntity>()
    const Loading = ref<Boolean>(false)
    const setting = ref<UserSetting>()

    // 用户头像URL  eg：avatar/c100782c-de9c-4c58-a72e-b47dba08bf36.jpg
    const avatarUrl: Ref<string | undefined> = computed(() => account.value?.avatar?.currentName ?? undefined)

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
     * 获取用户信息（同时刷新存储中的用户信息）
     */
    async function getUserInfo(): Promise<UserInfoEntity> {
      Loading.value = true
      try {
        const result = await queryUserInfo()
        account.value = result
        return result
      } catch (err: any) {
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
      if (authStatus.value != AuthStatus.NotLogin) return account.value as UserInfoEntity

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

    // async function loginByDeviceUid(): Promise<UserEntity> {
    //   if (Loading.value) return Promise.reject('loading..');
    //   Loading.value = true;
    //   console.log(`[DEBUG] 重新登陆获取TOKEN:${res.token}`);
    //   authStatus.value = AuthStatus.Login;
    //   auth.token = res.token;

    //   // 重连 websocket
    //   const socketStore = useWebSocketStore()
    //   socketStore.connect(res.token)
    //   return Promise.resolve(res);
    // }

    function logout() {
      console.log('DEBUG: logout')
      account.value = undefined
      localStorage.removeItem('deviceUid')
    }

    function getDeviceUid(): string {
      if (!import.meta.client) return ''

      var deviceUid = localStorage.getItem('deviceUid')
      if (deviceUid !== null) return deviceUid

      deviceUid = nanoid()
      localStorage.setItem('deviceUid', deviceUid)
      return deviceUid
    }
    return { login, logout, authStatus, account, getUserInfo, avatarUrl }
  },
  { persist: true }
)
