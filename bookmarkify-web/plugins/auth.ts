import { useUserStore } from '@stores/user.store'

export default defineNuxtPlugin((nuxtApp) => {
    const userStore = useUserStore()
    userStore.login()
})
