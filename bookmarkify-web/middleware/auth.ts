import { AuthStatusEnum } from '@typing'
import { useAuthStore } from '@stores/auth.store'

/**
 * 如果已经有帐户,则跳转到index
 *
 * 如果你因为发现明明有登录状态, 但依旧无法进入首页而找到了这里, 请检查以下几点:
 * 1.你是否清空了数据库,但是没有重启前端项目
 */
export default defineNuxtRouteMiddleware((to, from) => {
  const authStore = useAuthStore()
  // 必须是已认证用户(真实登录)才能使用;访客匿名会话(LOGGED)不算
  const isAuthed = AuthStatusEnum.AUTHED === authStore.authStatus

  // 引导页
  const welcomePage = '/welcome'
  // 用户控制台首页
  const consolePage = '/'
  // 受限页面列表
  const restrictPageList = ['/', '/setting', '/bookmark/similar', '/share/edit', '/access-token/docs']

  // 访问受限页面但未登录,跳转到引导页
  if (restrictPageList.includes(to.path) && !isAuthed) return navigateTo(welcomePage)
  // 访问引导页且已登录,跳转到首页
  if (to.path === welcomePage && isAuthed) return navigateTo(consolePage)
})
