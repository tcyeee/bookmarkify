<template>
  <button class="cy-btn cy-btn-ghost rounded-2xl" @click="openLogoutDialog">退出账号</button>

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
