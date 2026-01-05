import { AuthStatusEnum } from '@typing'
import { useAuthStore } from '@stores/auth.store'
const authStore = useAuthStore()
// 如果已经有帐户,则跳转到index
export default defineNuxtRouteMiddleware((to, from) => {
  const isNone = [AuthStatusEnum.NONE].includes(authStore.authStatus)

  // 引导页
  const welcomePage = '/welcome'
  // 用户控制台首页
  const consolePage = '/'
  // 受限页面列表
  const restrictPageList = ['/', '/setting']

  // 访问受限页面且没有登录,跳转到引导页
  if (restrictPageList.includes(to.path) && isNone) return navigateTo(welcomePage)
  // 访问引导页且已登录,跳转到首页
  if (to.path === welcomePage && !isNone) return navigateTo(consolePage)
})
