import FingerprintJS from '@fingerprintjs/fingerprintjs'
import { defineNuxtPlugin } from '#app'

export default defineNuxtPlugin(async () => {
    if (!import.meta.client) return
    const fp = await FingerprintJS.load()
    const result = await fp.get()
    const storeUser = useUserStore();
    storeUser.updateFingerprint(result.visitorId)
})