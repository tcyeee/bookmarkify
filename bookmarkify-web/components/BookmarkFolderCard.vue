<template>
  <div
    class="group w-full max-w-[420px] mx-auto rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800/40 p-4">
    <div class="flex items-center gap-2 mb-2">
      <Icon
        :icon="isRoot ? 'mdi:home-variant' : 'mdi:folder'"
        class="size-4 shrink-0"
        :class="isRoot ? 'text-slate-400 dark:text-slate-500' : 'text-amber-500'" />
      <input
        v-if="renaming"
        ref="renameInputRef"
        v-model="renameValue"
        type="text"
        maxlength="50"
        class="text-sm font-semibold text-slate-700 dark:text-slate-200 bg-transparent border-b border-primary focus:outline-none min-w-0 flex-1"
        @keyup.enter="submitRename"
        @keyup.esc="cancelRename"
        @blur="submitRename" />
      <span v-else class="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">{{ name }}</span>
      <span v-if="children.length" class="text-xs text-slate-400 dark:text-slate-500">({{ children.length }})</span>
      <button
        type="button"
        class="ml-auto shrink-0 opacity-0 group-hover:opacity-100 text-slate-400 hover:text-primary dark:hover:text-primary transition-colors"
        title="更多操作"
        @click="openMenu">
        <Icon icon="mdi:dots-vertical" class="size-4" />
      </button>
    </div>

    <div v-if="children.length === 0" class="text-xs text-slate-400 dark:text-slate-500 py-3 text-center">暂无书签</div>
    <template v-else>
      <BookmarkTreeRow
        v-for="child in children"
        :key="child.id"
        :node="child"
        :depth="0"
        @edit="(n: UserLayoutNodeVO) => emit('edit', n)" />
    </template>
  </div>
</template>

<script lang="ts" setup>
import { h, nextTick } from 'vue'
import { Icon } from '@iconify/vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksRenameDir, bookmarksDel } from '@api'
import type { UserLayoutNodeVO } from '@typing'
import BookmarkTreeRow from '@/components/BookmarkTreeRow.vue'

defineOptions({ name: 'BookmarkFolderCard' })

const props = defineProps<{ name: string; isRoot: boolean; folderId: string; children: UserLayoutNodeVO[] }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO]; share: [folderId: string] }>()

const bookmarkStore = useBookmarkStore()

// ── 重命名 ──
const renaming = ref(false)
const renameValue = ref('')
const renameInputRef = ref<HTMLInputElement | null>(null)

function startRename() {
  renameValue.value = props.name
  renaming.value = true
  nextTick(() => renameInputRef.value?.select())
}

function cancelRename() {
  renaming.value = false
}

async function submitRename() {
  if (!renaming.value) return
  renaming.value = false
  const name = renameValue.value.trim()
  if (!name || name === props.name) return
  try {
    await bookmarksRenameDir(props.folderId, name)
    if (bookmarkStore.nodes[props.folderId]) {
      bookmarkStore.nodes[props.folderId] = { ...bookmarkStore.nodes[props.folderId], name }
    }
    useToastStore().success('重命名成功')
  } catch (error) {
    console.error('[BookmarkFolderCard] 重命名文件夹失败', error)
  }
}

// ── 删除 ──
async function delFolder() {
  try {
    await useConfirmStore().confirm(`确定删除文件夹「${props.name}」及其中的 ${props.children.length} 个书签吗？`, {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await bookmarksDel([props.folderId])
    bookmarkStore.removeSubtree(props.folderId)
    useToastStore().success('删除成功')
  } catch (error) {
    console.error('[BookmarkFolderCard] 删除文件夹失败', error)
  }
}

type FolderMenuItem = NonNullable<Parameters<typeof ContextMenu.showContextMenu>[0]['items']>[number]

function openMenu(e: MouseEvent) {
  const items: FolderMenuItem[] = [
    {
      label: '分享',
      icon: h(Icon, { icon: 'mdi:share-variant-outline', class: 'size-4' }),
      onClick: () => emit('share', props.folderId),
    },
  ]
  if (!props.isRoot) {
    items.push(
      { label: '重命名', icon: h(Icon, { icon: 'mdi:pencil', class: 'size-4' }), onClick: () => startRename() },
      { label: '删除', icon: h(Icon, { icon: 'mdi:trash-can', class: 'size-4' }), onClick: () => delFolder() },
    )
  }
  ContextMenu.showContextMenu({ items, x: e.x, y: e.y })
}
</script>
