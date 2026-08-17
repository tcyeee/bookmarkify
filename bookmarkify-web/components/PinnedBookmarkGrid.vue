<template>
  <div ref="gridRef" class="flex flex-wrap gap-4">
    <!-- 磁贴外面必须再包一层：Pragmatic DnD 的 draggable 要挂在一个「不是链接」的元素上，
         而里面那个 <a> 反过来要 draggable="false" 关掉浏览器自带的「拖链接」行为，否则拖起来的
         是网址而不是磁贴（同 BookmarkTreeRow 的写法）。这一层也顺便承载左右的落点指示线。 -->
    <div
      v-for="node in nodes"
      :key="node.id"
      :data-pinned-id="node.id"
      class="relative cursor-grab active:cursor-grabbing transition-opacity"
      :class="{ 'opacity-40': draggingId === node.id }">
      <span
        v-if="dropTargetId === node.id && dropMode === 'before'"
        class="pointer-events-none absolute inset-y-0 -left-2 w-0.5 rounded-full bg-primary z-10" />
      <a
        :href="externalHref(node.typeApp!.urlFull)"
        target="_blank"
        rel="noopener noreferrer"
        draggable="false"
        class="group relative w-16 flex flex-col items-center gap-1 rounded-lg p-1.5 hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
        @click="recordOpen(node)"
        @contextmenu.prevent="openMenu($event.x, $event.y, node)">
        <BookmarkLogo :value="node.typeApp!" size="M" />
        <!-- 触屏没有右键，这个按钮是同一份菜单的第二个触发器（桌面上悬停才显形，观感不变） -->
        <button
          type="button"
          class="absolute -right-0.5 -top-0.5 reveal-on-hover flex size-6 items-center justify-center rounded-full bg-white/90 text-slate-500 shadow ring-1 ring-slate-200 transition-opacity hover:text-primary dark:bg-slate-800/90 dark:text-slate-300 dark:ring-slate-700"
          aria-label="书签操作"
          @click.stop.prevent="openMenuFromButton($event, node)">
          <Icon icon="mdi:dots-horizontal" class="size-4" />
        </button>
        <span
          class="w-full text-xs truncate text-center"
          :class="
            node.typeApp!.isActivity === false
              ? 'text-slate-400 dark:text-slate-500'
              : 'text-slate-600 dark:text-slate-300'
          ">
          {{ captionOf(node) }}
        </span>
      </a>
      <span
        v-if="dropTargetId === node.id && dropMode === 'after'"
        class="pointer-events-none absolute inset-y-0 -right-2 w-0.5 rounded-full bg-primary z-10" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { h, nextTick, onBeforeUnmount } from 'vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { Icon } from '@iconify/vue'
import { bookmarksDel, bookmarksPin, bookmarksPinSort, bookmarksRecordOpen } from '@api'
import type { UserLayoutNodeVO } from '@typing'
import { externalHref } from '@utils'
import { draggable, dropTargetForElements, monitorForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/combine'

defineOptions({ name: 'PinnedBookmarkGrid' })

const props = defineProps<{ nodes: UserLayoutNodeVO[] }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO] }>()

const bookmarkStore = useBookmarkStore()

// 置顶区是「大图 + 一行短文案」的磁贴形态，文案取服务端按 TILE 模式算好的 tileTitle
// （首页书签即站点短名）。桌面下方那份列表行仍用 title —— 同一条书签在首页被渲染两次，
// 两处该显示的文案本来就不同，详见后端 BookmarkShow.tileTitle。
function captionOf(node: UserLayoutNodeVO) {
  const app = node.typeApp!
  return app.tileTitle || app.title || app.urlBase
}

function recordOpen(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  bookmarksRecordOpen(node.typeApp.bookmarkId).catch(() => {})
}

// ── 拖动排序 ──
// 顺序记在书签自己身上（bookmark.pinned_sort），不是 store 的 order 顺序表：置顶区不是树上的
// 一层，而是从各个文件夹里筛出来平铺的视图，它的先后在按层记的 order 里表达不出来。
// 与文件夹卡片/文件夹排序两套拖拽靠 source.data.kind 互斥：本文件只认 'pinned-tile'，
// 而 BookmarkFolderCard 的行/卡片放置区只认 'bookmark-row'（那道白名单是必须的——置顶书签
// 同时也是某个文件夹的子项，光看 id 两边分不开，一次置顶区拖拽会被当成移动书签处理）。
const gridRef = ref<HTMLElement | null>(null)
const draggingId = ref<string | null>(null)
const dropTargetId = ref<string | null>(null)
const dropMode = ref<'before' | 'after' | null>(null)

let tilesCleanup: (() => void) | null = null
let monitorCleanup: (() => void) | null = null

// 横向平铺，按左右半边判定落点（列表是上下半边）
function classify(el: HTMLElement, clientX: number): 'before' | 'after' {
  const r = el.getBoundingClientRect()
  return clientX - r.left < r.width / 2 ? 'before' : 'after'
}

function reset() {
  draggingId.value = null
  dropTargetId.value = null
  dropMode.value = null
}

function registerTiles() {
  tilesCleanup?.()
  tilesCleanup = null
  const root = gridRef.value
  if (!root) return
  const disposers = Array.from(root.querySelectorAll<HTMLElement>('[data-pinned-id]')).map((el) => {
    const id = el.dataset.pinnedId!
    return combine(
      draggable({
        element: el,
        getInitialData: () => ({ kind: 'pinned-tile', id }),
        onDragStart: () => (draggingId.value = id),
        onDrop: reset,
      }),
      dropTargetForElements({
        element: el,
        canDrop: ({ source }) => source.data.kind === 'pinned-tile' && source.data.id !== id,
        getData: () => ({ kind: 'pinned-tile', id }),
        onDrag: ({ location }) => {
          if (location.current.dropTargets[0]?.element !== el) return
          dropTargetId.value = id
          dropMode.value = classify(el, location.current.input.clientX)
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
  tilesCleanup = combine(...disposers)
}

function registerMonitor() {
  monitorCleanup?.()
  monitorCleanup = monitorForElements({
    onDrop: ({ source, location }) => {
      reset()
      if (source.data.kind !== 'pinned-tile') return
      const target = location.current.dropTargets[0]
      if (!target || target.data.kind !== 'pinned-tile') return
      const sourceId = String(source.data.id)
      const targetId = String(target.data.id)
      if (sourceId === targetId) return
      const mode = classify(target.element as HTMLElement, location.current.input.clientX)
      applyReorder(sourceId, targetId, mode)
    },
  })
}

function applyReorder(sourceId: string, targetId: string, mode: 'before' | 'after') {
  const ids = props.nodes.map((n) => n.id)
  const sourceIndex = ids.indexOf(sourceId)
  const targetIndex = ids.indexOf(targetId)
  if (sourceIndex < 0 || targetIndex < 0) return
  let insertIndex = mode === 'after' ? targetIndex + 1 : targetIndex
  ids.splice(sourceIndex, 1)
  if (sourceIndex < insertIndex) insertIndex -= 1
  ids.splice(Math.max(0, Math.min(insertIndex, ids.length)), 0, sourceId)
  persistOrder(ids)
}

/** 本地先落位（乐观更新），再把整份顺序写回后端；失败时刷新拿回权威顺序，不留一个假的排布。 */
function persistOrder(nodeIds: string[]) {
  bookmarkStore.reorderPinnedLocal(nodeIds)
  // 后端要的是 bookmarkId（用户与页面的关联行），不是布局节点 id —— 置顶/取消置顶用的也是它
  const linkIds = nodeIds.map((id) => bookmarkStore.nodes[id]?.typeApp?.bookmarkId).filter(Boolean) as string[]
  bookmarksPinSort(linkIds).catch((error) => {
    console.error('[PinnedBookmarkGrid] 置顶排序保存失败', error)
    bookmarkStore.refresh('置顶排序保存失败')
  })
}

/** 左移/右移一格。触屏与键盘用户没有 HTML5 拖放，右键菜单是他们唯一的排序入口（同书签行的上移/下移）。 */
function moveBy(node: UserLayoutNodeVO, delta: number) {
  const ids = props.nodes.map((n) => n.id)
  const from = ids.indexOf(node.id)
  const to = from + delta
  if (from < 0 || to < 0 || to >= ids.length) return
  ids.splice(from, 1)
  ids.splice(to, 0, node.id)
  persistOrder(ids)
}

// 组件挂载后节点还会变（置顶/取消置顶、WS 推送替换节点对象），每次都要重新登记：
// 元素被 Vue 卸载重建后，旧的登记指向的是已脱离文档的元素，表现是「刚置顶完就拖不动了」
watch(() => props.nodes.map((n) => n.id).join(','), () => nextTick(registerTiles))
onMounted(() =>
  nextTick(() => {
    registerTiles()
    registerMonitor()
  }),
)
onBeforeUnmount(() => {
  tilesCleanup?.()
  monitorCleanup?.()
})

async function unpinOne(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  try {
    await bookmarksPin(node.typeApp.bookmarkId, false)
    bookmarkStore.setPinnedLocal(node.id, false)
    useToastStore().success('已取消置顶')
  } catch (error) {
    console.error('[PinnedBookmarkGrid] 取消置顶失败', error)
  }
}

async function delOne(node: UserLayoutNodeVO) {
  try {
    await useConfirmStore().confirm(`确定删除书签「${node.typeApp?.title || node.typeApp?.urlBase}」吗？`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await bookmarksDel([node.id])
    bookmarkStore.removeNode(node.id)
    useToastStore().success('删除成功')
  } catch (error) {
    console.error('[PinnedBookmarkGrid] 删除书签失败', error)
  }
}

/** 右键与磁贴角上的 ⋯ 共用同一份菜单定义 —— 复制一份的话，以后加菜单项必然漏改一处 */
function openMenu(x: number, y: number, node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  const index = props.nodes.findIndex((n) => n.id === node.id)
  ContextMenu.showContextMenu({
    items: [
      { label: '取消置顶', icon: h(Icon, { icon: 'mdi:pin-off', class: 'size-4' }), onClick: () => unpinOne(node) },
      { label: '修改', icon: h(Icon, { icon: 'mdi:pencil', class: 'size-4' }), onClick: () => emit('edit', node) },
      // divided 的布尔值等价于 'down'（分割线画在自己下面），这里要的是画在自己上面，必须显式写 'up'
      {
        label: '左移',
        icon: h(Icon, { icon: 'mdi:arrow-left', class: 'size-4' }),
        divided: 'up',
        disabled: index <= 0,
        onClick: () => moveBy(node, -1),
      },
      {
        label: '右移',
        icon: h(Icon, { icon: 'mdi:arrow-right', class: 'size-4' }),
        disabled: index < 0 || index >= props.nodes.length - 1,
        onClick: () => moveBy(node, 1),
      },
      { label: '删除', icon: h(Icon, { icon: 'mdi:trash-can', class: 'size-4' }), divided: 'up', onClick: () => delOne(node) },
    ],
    x,
    y,
  })
}

/** 按钮触发时用按钮自身的位置，而不是点击坐标 —— 后者在触屏上会把菜单顶到手指底下 */
function openMenuFromButton(e: MouseEvent, node: UserLayoutNodeVO) {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  openMenu(rect.right, rect.bottom, node)
}
</script>
