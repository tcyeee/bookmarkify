<template>
  <div class="group w-full rounded-lg bg-slate-50 dark:bg-slate-800/40 p-4">
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
    <div v-else ref="listRef">
      <div
        v-for="child in children"
        :key="child.id"
        :data-row-id="child.id"
        class="relative cursor-grab active:cursor-grabbing"
        :class="{ 'opacity-40': draggingId === child.id }">
        <span
          v-if="dropTargetId === child.id && dropMode === 'above'"
          class="pointer-events-none absolute inset-x-0 -top-px h-0.5 rounded-full bg-primary z-10" />
        <BookmarkTreeRow :node="child" :depth="0" @edit="(n: UserLayoutNodeVO) => emit('edit', n)" />
        <span
          v-if="dropTargetId === child.id && dropMode === 'below'"
          class="pointer-events-none absolute inset-x-0 -bottom-px h-0.5 rounded-full bg-primary z-10" />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { h, nextTick, onBeforeUnmount } from 'vue'
import { Icon } from '@iconify/vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksRenameDir, bookmarksDel, bookmarksSort } from '@api'
import { ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import BookmarkTreeRow from '@/components/BookmarkTreeRow.vue'
import { draggable, dropTargetForElements, monitorForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/combine'

defineOptions({ name: 'BookmarkFolderCard' })

const props = defineProps<{ name: string; isRoot: boolean; folderId: string; children: UserLayoutNodeVO[] }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO]; share: [folderId: string] }>()

const bookmarkStore = useBookmarkStore()

// ── 文件夹内书签拖动排序 ──
// 根目录卡片（isRoot）在 store 顺序表中对应 ROOT_KEY，而非 index.vue 里仅用于 UI 分组的 ROOT_CARD_ID
const orderKey = computed(() => (props.isRoot ? ROOT_KEY : props.folderId))

const listRef = ref<HTMLElement | null>(null)
const draggingId = ref<string | null>(null)
const dropTargetId = ref<string | null>(null)
const dropMode = ref<'above' | 'below' | null>(null)

let rowsCleanup: (() => void) | null = null
let monitorCleanup: (() => void) | null = null

function classify(el: HTMLElement, clientY: number): 'above' | 'below' {
  const r = el.getBoundingClientRect()
  return clientY - r.top < r.height / 2 ? 'above' : 'below'
}

function reset() {
  draggingId.value = null
  dropTargetId.value = null
  dropMode.value = null
}

function registerRows() {
  rowsCleanup?.()
  const root = listRef.value
  const rows = Array.from(root?.querySelectorAll<HTMLElement>('[data-row-id]') ?? [])
  const disposers = rows.map((el) => {
    const id = el.dataset.rowId!
    return combine(
      draggable({
        element: el,
        getInitialData: () => ({ id }),
        onDragStart: () => (draggingId.value = id),
        onDrop: reset,
      }),
      dropTargetForElements({
        element: el,
        canDrop: ({ source }) => source.data.id !== id && props.children.some((c) => c.id === source.data.id),
        getData: () => ({ id }),
        onDrag: ({ location }) => {
          dropTargetId.value = id
          dropMode.value = classify(el, location.current.input.clientY)
        },
        onDragLeave: () => {
          if (dropTargetId.value === id) {
            dropTargetId.value = null
            dropMode.value = null
          }
        },
      }),
    )
  })
  rowsCleanup = combine(...disposers)
}

function registerMonitor() {
  monitorCleanup?.()
  monitorCleanup = monitorForElements({
    onDrop: ({ source, location }) => {
      reset()
      const target = location.current.dropTargets[0]
      if (!target) return
      const sourceId = String(source.data.id)
      const targetId = String(target.data.id)
      if (sourceId === targetId) return
      if (!props.children.some((c) => c.id === sourceId) || !props.children.some((c) => c.id === targetId)) return
      const mode = classify(target.element as HTMLElement, location.current.input.clientY)
      decide(sourceId, targetId, mode)
    },
  })
}

// 把某个子集合的新顺序，套用回 fullOld（可能包含子集之外的其它 id，如根目录卡片里的文件夹 id）：
// 子集内 id 按 newSubsetOrder 重新排列，非子集 id 保留原位置不动
function applySubsetOrder(fullOld: string[], newSubsetOrder: string[]): string[] {
  const subset = new Set(newSubsetOrder)
  const queue = [...newSubsetOrder]
  return fullOld.map((id) => (subset.has(id) ? (queue.shift() as string) : id))
}

function decide(sourceId: string, targetId: string, mode: 'above' | 'below') {
  const visibleIds = props.children.map((c) => c.id).filter((x) => x !== sourceId)
  const ti = visibleIds.indexOf(targetId)
  if (ti < 0) return
  visibleIds.splice(mode === 'below' ? ti + 1 : ti, 0, sourceId)
  const fullOld = bookmarkStore.order[orderKey.value] ?? visibleIds
  bookmarkStore.reorderLocal(orderKey.value, applySubsetOrder(fullOld, visibleIds))
  bookmarksSort(bookmarkStore.fullOrderParams).catch((error) => console.error('[BookmarkFolderCard] 排序保存失败', error))
}

watch(
  () => props.children.map((c) => c.id).join(','),
  () => nextTick(registerRows),
)
onMounted(() =>
  nextTick(() => {
    registerRows()
    registerMonitor()
  }),
)
onBeforeUnmount(() => {
  rowsCleanup?.()
  monitorCleanup?.()
})

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
