<template>
  <dialog ref="dialogRef" class="cy-modal">
    <div class="cy-modal-box max-w-sm">
      <h3 class="text-base font-semibold text-slate-800 dark:text-slate-100">{{ store.title }}</h3>
      <p v-if="movingLabel" class="mt-1 text-sm text-slate-500 dark:text-slate-400 truncate">{{ movingLabel }}</p>

      <div class="mt-4 max-h-72 overflow-y-auto rounded-lg border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-800">
        <button
          v-for="target in targets"
          :key="target.key"
          type="button"
          class="w-full flex items-center gap-3 px-3 py-2.5 text-left hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          :disabled="target.key === currentParentKey"
          @click="store.accept(target.key)">
          <Icon
            :icon="target.key === ROOT_KEY ? 'mdi:folder-home' : 'mdi:folder'"
            class="size-4 shrink-0"
            :class="target.key === ROOT_KEY ? 'text-slate-700 dark:text-slate-200' : 'text-amber-500'" />
          <span class="text-sm text-slate-700 dark:text-slate-200 truncate flex-1">{{ target.name }}</span>
          <span v-if="target.key === currentParentKey" class="text-xs text-slate-400 shrink-0">当前位置</span>
        </button>
      </div>

      <div class="cy-modal-action">
        <button class="cy-btn cy-btn-ghost" @click="store.dismiss()">取消</button>
      </div>
    </div>
    <form method="dialog" class="cy-modal-backdrop">
      <button>close</button>
    </form>
  </dialog>
</template>

<script lang="ts" setup>
import { HomeItemType, ROOT_KEY } from '@typing'
import { useMovePickerStore } from '@stores/movePicker.store'

const store = useMovePickerStore()
const bookmarkStore = useBookmarkStore()
const dialogRef = ref<HTMLDialogElement | null>(null)

const movingLabel = computed(() => {
  const node = bookmarkStore.nodes[store.nodeId]
  return node?.typeApp?.title || node?.typeApp?.urlBase || node?.name || ''
})

const currentParentKey = computed(() => bookmarkStore.parentKeyOf(store.nodeId) ?? ROOT_KEY)

// 根目录 + 所有顶层文件夹。不列出文件夹里的文件夹：这个产品的目录结构本身只有一层
// （createDir 只能由根目录下的书签合并而来），列出更深的层级只会给出无法抵达的选项。
const targets = computed(() => [
  { key: ROOT_KEY, name: '根目录' },
  ...bookmarkStore.rootNodes
    .filter((node) => node.type === HomeItemType.BOOKMARK_DIR)
    .map((dir) => ({ key: dir.id, name: dir.name || '文件夹' })),
])

watch(
  () => store.visible,
  (visible) => {
    if (!dialogRef.value) return
    if (visible) dialogRef.value.showModal()
    else dialogRef.value.close()
  }
)

// Esc / 点击遮罩关闭：原生 dialog 的 close 事件统一走取消路径
function onCancel() {
  store.dismiss()
}

onMounted(() => {
  dialogRef.value?.addEventListener('close', onCancel)
})

onBeforeUnmount(() => {
  dialogRef.value?.removeEventListener('close', onCancel)
})
</script>
