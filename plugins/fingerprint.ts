import FingerprintJS from '@fingerprintjs/fingerprintjs'
import { defineNuxtPlugin } from '#app'
import { StoreUser } from "@/stores/user.store";

export default defineNuxtPlugin(async () => {
    if (!import.meta.client) return
    const fp = await FingerprintJS.load()
    const result = await fp.get()
    const storeUser = StoreUser();
    storeUser.updateFingerprint(result.visitorId)
})