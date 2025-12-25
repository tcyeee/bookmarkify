<template>
  <div
    class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
    <div class="flex items-center gap-3 text-slate-800 dark:text-slate-200">
      <span class="icon--memory-arrow-down-right-box icon-size-22 text-slate-500 dark:text-slate-400"></span>
      <div>
        <div class="font-semibold">退出登录</div>
        <div class="text-sm text-slate-500 dark:text-slate-400">仅退出当前设备登录，不影响账号数据。</div>
      </div>
    </div>
    <button class="cy-btn cy-btn-ghost rounded-2xl" @click="accountLogout()">退出账号</button>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { AuthStatusEnum } from '@typing'

const userStore = useUserStore()
const isAuthed = computed(() => userStore.authStatus === AuthStatusEnum.AUTHED)

async function accountLogout() {
  if (!isAuthed.value) {
    try {
      await ElMessageBox.confirm('您还没设置任何登录方式,一旦退出,将无法登录', '确认退出', {
        confirmButtonText: '继续退出',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  await userStore.logout()
}
</script>
