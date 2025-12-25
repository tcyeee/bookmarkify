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
    <button class="cy-btn cy-btn-ghost rounded-2xl" @click="openLogoutDialog">退出账号</button>
  </div>

  <dialog id="logoutDialog" ref="logoutDialogRef" class="cy-modal">
    <div class="cy-modal-box">
      <h3 class="text-lg font-bold">确认退出</h3>
      <p class="py-4">
        <span v-if="isAuthed">确定要退出当前设备吗？</span>
        <span v-else class="text-sm text-gray-500">您还没设置任何登录方式，一旦退出将无法登录。</span>
      </p>
      <div class="cy-modal-action">
        <form method="dialog">
          <button class="cy-btn cy-btn-ghost">取消</button>
        </form>
        <button class="cy-btn cy-btn-error" @click="confirmLogout">确认退出</button>
      </div>
    </div>
  </dialog>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { AuthStatusEnum } from '@typing'

const logoutDialogRef = ref<HTMLDialogElement | null>(null)
const userStore = useUserStore()
const sysStore = useSysStore()
const isAuthed = computed(() => userStore.authStatus === AuthStatusEnum.AUTHED)

function handleDialogClose() {
  sysStore.togglePreventKeyEventsFlag(false)
}

function openLogoutDialog() {
  if (!import.meta.client) return
  const dialog = logoutDialogRef.value
  sysStore.togglePreventKeyEventsFlag(true)
  dialog?.showModal()
}

function closeLogoutDialog() {
  if (!import.meta.client) return
  const dialog = logoutDialogRef.value
  handleDialogClose()
  dialog?.close()
}

onMounted(() => {
  const dialog = logoutDialogRef.value
  dialog?.addEventListener('close', handleDialogClose)
  dialog?.addEventListener('cancel', handleDialogClose)
})

onBeforeUnmount(() => {
  const dialog = logoutDialogRef.value
  dialog?.removeEventListener('close', handleDialogClose)
  dialog?.removeEventListener('cancel', handleDialogClose)
})

async function confirmLogout() {
  await userStore.logout()
  closeLogoutDialog()
}
</script>
