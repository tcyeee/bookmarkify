<template>
  <div
    ref="cardRef"
    class="folder-card w-full rounded-lg bg-slate-50 dark:bg-slate-800/40 p-4 transition-shadow"
    :class="{ 'ring-2 ring-primary/60': dropTargetId === CARD_END_ID }">
    <div class="flex items-center gap-2" :class="collapsed ? '' : 'mb-2'">
      <Icon
        v-if="!isRoot"
        data-folder-handle
        icon="mdi:drag-vertical"
        class="size-4 shrink-0 -ml-1 cursor-grab active:cursor-grabbing text-slate-300 hover:text-slate-400 dark:text-slate-600 dark:hover:text-slate-500" />
      <button
        type="button"
        class="shrink-0 text-slate-400 hover:text-primary dark:text-slate-500 dark:hover:text-primary"
        :aria-expanded="!collapsed"
        :title="collapsed ? '展开' : '折叠'"
        @click="toggleCollapsed">
        <Icon :icon="collapsed ? 'mdi:chevron-right' : 'mdi:chevron-down'" class="size-4" />
      </button>
      <Icon
        :icon="isRoot ? 'mdi:home-variant' : collapsed ? 'mdi:folder' : 'mdi:folder-open'"
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
      <!-- 标题本身也是折叠开关：箭头只有 16px，而「点标题展开/收起」是文件树的通用手势 -->
      <span
        v-else
        class="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate cursor-pointer"
        @click="toggleCollapsed">
        {{ name }}
      </span>
      <span v-if="children.length" class="text-xs text-slate-400 dark:text-slate-500">({{ children.length }})</span>
      <button
        type="button"
        class="ml-auto shrink-0 reveal-on-hover-folder text-slate-400 hover:text-primary dark:hover:text-primary transition-opacity transition-colors"
        title="更多操作"
        @click="openMenu">
        <Icon icon="mdi:dots-vertical" class="size-4" />
      </button>
    </div>

    <!-- 折叠时整份列表卸载（而不是 v-show 藏起来）：行是拖拽放置区，留在 DOM 里会让人把书签
         "拖进"一个看不见的位置。卡片容器自身的放置区仍在，所以折叠状态下依然能收下书签，
         落到该文件夹末尾。列表重新挂载后要补一次 registerRows，见下方 watch。 -->
    <template v-if="!collapsed">
      <div
        v-if="children.length === 0"
        class="text-xs text-slate-400 dark:text-slate-500 py-3 text-center rounded border border-dashed"
        :class="dropTargetId === CARD_END_ID ? 'border-primary text-primary' : 'border-transparent'">
        暂无书签
      </div>
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
        <span v-if="dropTargetId === CARD_END_ID" class="block h-0.5 rounded-full bg-primary mt-1" />
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { h, nextTick, onBeforeUnmount } from 'vue'
import { Icon } from '@iconify/vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksRenameDir, bookmarksDel, bookmarksSort, bookmarksMoveNode } from '@api'
import { ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import BookmarkTreeRow from '@/components/BookmarkTreeRow.vue'
import { draggable, dropTargetForElements, monitorForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/combine'

defineOptions({ name: 'BookmarkFolderCard' })

const props = defineProps<{ name: string; isRoot: boolean; folderId: string; children: UserLayoutNodeVO[] }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO]; share: [folderId: string] }>()

const bookmarkStore = useBookmarkStore()

// ── 折叠 ──
// 折叠状态按卡片 id 记在 store 里（根目录卡片传进来的是 index.vue 的 ROOT_CARD_ID，不是 ROOT_KEY），
// 随 localStorage 持久化：这是个用户主动摆好的视图，F5 之后弹回全展开等于每次都要重收一遍。
const collapsed = computed(() => bookmarkStore.isFolderCollapsed(props.folderId))

function toggleCollapsed() {
  bookmarkStore.toggleFolderCollapsed(props.folderId)
}

// ── 书签拖动排序 / 跨文件夹移动 ──
// 根目录卡片（isRoot）在 store 顺序表中对应 ROOT_KEY，而非 index.vue 里仅用于 UI 分组的 ROOT_CARD_ID
const orderKey = computed(() => (props.isRoot ? ROOT_KEY : props.folderId))
// 卡片容器本身也是放置区，代表"拖到本卡片末尾"：命中具体行之外的空白处（或空文件夹）时用它兜底
const CARD_END_ID = '__card_end__'

const cardRef = ref<HTMLElement | null>(null)
const listRef = ref<HTMLElement | null>(null)
const draggingId = ref<string | null>(null)
const dropTargetId = ref<string | null>(null)
const dropMode = ref<'above' | 'below' | null>(null)

let rowsCleanup: (() => void) | null = null
let cardCleanup: (() => void) | null = null
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
        getInitialData: () => ({ kind: 'bookmark-row', id }),
        onDragStart: () => (draggingId.value = id),
        onDrop: reset,
      }),
      dropTargetForElements({
        element: el,
        // 不再要求 source 必须已属于本卡片：允许从其它文件夹/根目录拖入，实现跨文件夹移动。
        // 但只收「书签行」这一种来源，其余两套拖拽各有自己的放置区：文件夹卡片级别的拖拽
        // （kind === 'folder-card'，见 pages/index.vue）走卡片外层包装 div，置顶区磁贴
        // （kind === 'pinned-tile'）只落在置顶区自己身上。**这里必须是白名单**：置顶书签同时
        // 也是某个文件夹的子项，光看 id 两边完全分不开，漏掉一种来源就会让一次置顶区拖拽被
        // 当成「把书签移进这个文件夹」执行掉。
        canDrop: ({ source }) => source.data.kind === 'bookmark-row' && source.data.id !== id,
        getData: () => ({ id }),
        onDrag: ({ location }) => {
          // dropTargets[0] 是嵌套放置区里被命中的最内层元素；只有真正悬停在本行上时才更新指示线，
          // 避免与外层卡片容器的放置区（CARD_END_ID）互相覆盖闪烁
          if (location.current.dropTargets[0]?.element !== el) return
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

function registerCard() {
  cardCleanup?.()
  const el = cardRef.value
  if (!el) return
  cardCleanup = dropTargetForElements({
    element: el,
    // 同上：只收书签行，文件夹卡片拖拽交给 pages/index.vue 里包装 div 上的 dropTarget，
    // 置顶区磁贴不落到卡片里
    canDrop: ({ source }) => source.data.kind === 'bookmark-row',
    getData: () => ({ id: CARD_END_ID }),
    onDrag: ({ location }) => {
      if (location.current.dropTargets[0]?.element !== el) return
      dropTargetId.value = CARD_END_ID
      dropMode.value = null
    },
    onDragLeave: () => {
      if (dropTargetId.value === CARD_END_ID) {
        dropTargetId.value = null
        dropMode.value = null
      }
    },
  })
}

function registerMonitor() {
  monitorCleanup?.()
  monitorCleanup = monitorForElements({
    onDrop: ({ source, location }) => {
      reset()
      // 全局 monitor 收到的是**所有**拖拽，包括置顶区磁贴与文件夹卡片；它们的 id 就是节点 id，
      // 与本卡片的子项 id 同一个取值空间，不看 kind 的话下面那道 belongsToThisCard 会认成自家行
      if (source.data.kind !== 'bookmark-row') return
      const target = location.current.dropTargets[0]
      if (!target) return
      const sourceId = String(source.data.id)
      const targetId = String(target.data.id)
      if (sourceId === targetId) return
      // 全局 monitor：每个卡片实例都会收到同一次 drop，这里只处理真正落在"本卡片"范围内的那次
      // （命中本卡片自己的某一行，或命中本卡片容器本身），避免被多个卡片实例重复处理
      const belongsToThisCard =
        targetId === CARD_END_ID ? target.element === cardRef.value : props.children.some((c) => c.id === targetId)
      if (!belongsToThisCard) return
      const mode = targetId === CARD_END_ID ? null : classify(target.element as HTMLElement, location.current.input.clientY)
      decide(sourceId, targetId, mode)
    },
  })
}

function decide(sourceId: string, targetId: string, mode: 'above' | 'below' | null) {
  const destKey = orderKey.value
  const fromKey = bookmarkStore.parentKeyOf(sourceId)
  if (fromKey == null) return
  const destOrder = bookmarkStore.order[destKey] ?? []
  const targetIndex = targetId === CARD_END_ID ? destOrder.length : destOrder.indexOf(targetId)
  if (targetIndex < 0) return
  let insertIndex = targetId === CARD_END_ID ? destOrder.length : mode === 'below' ? targetIndex + 1 : targetIndex

  if (fromKey === destKey) {
    // 同一文件夹内重排：直接在该文件夹的全量顺序表里挪位置（含根目录卡片里穿插的文件夹 id，位置不受影响）
    const sourceIndex = destOrder.indexOf(sourceId)
    if (sourceIndex < 0) return
    const next = [...destOrder]
    next.splice(sourceIndex, 1)
    if (sourceIndex < insertIndex) insertIndex -= 1
    next.splice(Math.max(0, Math.min(insertIndex, next.length)), 0, sourceId)
    bookmarkStore.reorderLocal(destKey, next)
  } else {
    // 跨文件夹移动：moveLocal 会把节点从原文件夹顺序表中移出、插入目标文件夹指定位置，
    // 并在原文件夹因此只剩 ≤1 项时自动就地解散
    bookmarkStore.moveLocal(sourceId, destKey, insertIndex)
  }

  bookmarksSort(bookmarkStore.fullOrderParams).catch((error) => console.error('[BookmarkFolderCard] 排序保存失败', error))
  if (fromKey !== destKey) {
    bookmarksMoveNode(sourceId, destKey === ROOT_KEY ? null : destKey).catch((error) =>
      console.error('[BookmarkFolderCard] 移动书签失败', error),
    )
  }
}

// collapsed 一并盯住：折叠会把整份列表连同 listRef 卸载，展开时挂回来的是一批全新的元素，
// 不补注册的话表现是「收起再展开之后，这个文件夹里的书签就拖不动了」——和 index.vue 里
// 盯 folderColumns 的那处是同一个坑。
watch(
  [() => props.children.map((c) => c.id).join(','), collapsed],
  () => nextTick(registerRows),
)
onMounted(() =>
  nextTick(() => {
    registerRows()
    registerCard()
    registerMonitor()
  }),
)
onBeforeUnmount(() => {
  rowsCleanup?.()
  cardCleanup?.()
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
    bookmarkStore.renameFolderLocal(props.folderId, name)
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
      // 走的是与工具栏「新增书签」同一个弹窗，区别只在落点：加完直接进这个文件夹。
      // 根目录卡片的 folderId 是 index.vue 里仅供 UI 分组用的 ROOT_CARD_ID，不是节点 id，
      // 传给后端会当场 E102，所以这里必须换成 null（= 根目录）
      label: '添加书签',
      icon: h(Icon, { icon: 'mdi:plus', class: 'size-4' }),
      onClick: () => useSysStore().openAddBookmarkDialog(props.isRoot ? null : props.folderId),
    },
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
