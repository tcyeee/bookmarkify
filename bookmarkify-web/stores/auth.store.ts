import { defineStore } from 'pinia'
import {
    AuthStatusEnum,
    type EmailVerifyParams,
    type SmsVerifyParams,
    type UserInfo,
} from '@typing'
import { authLogout, captchaVerifyEmail, captchaVerifySms, queryUserInfo, track } from '@api'
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
                return result
            } catch (err: any) {
                return Promise.reject(err)
            }
        },

        async loginWithPhone(params: SmsVerifyParams): Promise<UserInfo> {
            try {
                // 手机短信验证码登录/注册，成功后合并到当前账号信息
                const result = await captchaVerifySms(params)
                this.account = { ...this.account, ...result }
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
                return result
            } catch (err: any) {
                if (err.code == 202) {
                    await this.logout()
                    ElMessage.error('用户信息已过期,已重新登录,请刷新页面')
                    return await this.loginOrRegister()
                }
                ElMessage.error(err.message || '刷新用户信息失败')
                throw err
            }
        },

        async loginOrRegister(): Promise<UserInfo> {
            console.log('DEBUG: 登录或者注册，刷新最新用户信息')
            // track 返回最新用户 token；用于初次登录或 token 续期
            const user = await track()
            this.account = { ...this.account, ...user }
            if (!user.token) return Promise.reject('登陆数据异常')

            // 登录后刷新书签与 WebSocket 连接
            const bookmarkStore = useBookmarkStore()
            bookmarkStore.update()

            const webSocketStore = useWebSocketStore()
            webSocketStore.connect(user.token)

            const preferenceStore = usePreferenceStore()
            preferenceStore.fetchPreference()

            return Promise.resolve(this.account as UserInfo)
        },

        async logout() {
            console.log('DEBUG: 退出登陆')
            const webSocketStore = useWebSocketStore()
            const bookmarkStore = useBookmarkStore()
            const sysStore = useSysStore()
            const preferenceStore = usePreferenceStore()

            try {
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
                    document.cookie = 'user=;deviceUid=; Max-Age=0; path=/'
                }

                navigateTo('/welcome')
            }
        },
    },

    persist: true,
})

