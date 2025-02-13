// 自动通过设备ID创建用户
import { StoreUser } from "@/stores/user.store";
export default defineNuxtPlugin((nuxtApp) => {
    if (!import.meta.client) return
    const storeUser = StoreUser();
    storeUser.loginByDeviceUid()
})
