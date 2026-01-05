export default defineNuxtRouteMiddleware((to, _from) => {
  const token = useCookie('auth_token')

  // console.log('Auth Middleware:', to.path, token.value)

  if (!token.value && to.path !== '/login') {
    return navigateTo('/login')
  }

  if (token.value && to.path === '/login') {
    return navigateTo('/')
  }
})
