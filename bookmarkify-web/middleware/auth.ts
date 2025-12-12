import { AuthStatusEnum } from '@typing'
const userStore = useUserStore()
// 如果已经有帐户,则跳转到index
export default defineNuxtRouteMiddleware((to, from) => {
  const isNone = [AuthStatusEnum.NONE].includes(userStore.authStatus)

  // 如果当前是/index页面
  if (to.path === '/' && isNone) {
    console.log('[DEBUG] 当前是/index页面,没有帐户,跳转到引导页')
    return navigateTo('/welcome')
  }

  // 如果当前是/welcome页面
  if (to.path === '/welcome' && !isNone) {
    console.log('[DEBUG] 当前是/welcome页面,已经有帐户,跳转到index')
    return navigateTo('/')
  }
})
