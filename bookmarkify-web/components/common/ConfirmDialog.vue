<template>
  <dialog ref="dialogRef" class="cy-modal">
    <div class="cy-modal-box max-w-sm">
      <div class="flex items-start gap-3">
        <Icon :icon="iconName" class="size-6 shrink-0" :class="iconColorClass" />
        <div>
          <h3 class="text-base font-semibold text-slate-800 dark:text-slate-100">{{ store.title }}</h3>
          <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">{{ store.message }}</p>
        </div>
      </div>

      <div class="cy-modal-action mt-6">
        <button class="cy-btn cy-btn-ghost" @click="onCancel">{{ store.cancelText }}</button>
        <button class="cy-btn" :class="confirmBtnClass" @click="onConfirm">{{ store.confirmText }}</button>
      </div>
    </div>
    <form method="dialog" class="cy-modal-backdrop">
      <button>close</button>
    </form>
  </dialog>
</template>

<script lang="ts" setup>
import { useConfirmStore } from '@stores/confirm.store'

const store = useConfirmStore()
const dialogRef = ref<HTMLDialogElement | null>(null)

watch(
  () => store.visible,
  (visible) => {
    if (!dialogRef.value) return
    if (visible) dialogRef.value.showModal()
    else dialogRef.value.close()
  }
)

function onConfirm() {
  store.accept()
}

function onCancel() {
  store.dismiss()
}

const iconName = computed(() =>
  store.type === 'error' ? 'mdi:close-circle' : store.type === 'info' ? 'mdi:information' : 'mdi:alert-box'
)
const iconColorClass = computed(() =>
  store.type === 'error' ? 'text-red-500' : store.type === 'info' ? 'text-sky-500' : 'text-amber-500'
)
const confirmBtnClass = computed(() =>
  store.type === 'error' ? 'cy-btn-error' : store.type === 'info' ? 'cy-btn-primary' : 'cy-btn-warning'
)

// Esc / 点击遮罩关闭：原生 dialog 的 close 事件统一走取消路径
onMounted(() => {
  dialogRef.value?.addEventListener('close', onCancel)
})

onBeforeUnmount(() => {
  dialogRef.value?.removeEventListener('close', onCancel)
})
</script>
