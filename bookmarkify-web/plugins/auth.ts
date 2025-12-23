export default defineNuxtPlugin(async () => {
  const userStore = useUserStore()
  console.log(`DEBUG: 帐户状态为: ${userStore.authStatus}`)
})
