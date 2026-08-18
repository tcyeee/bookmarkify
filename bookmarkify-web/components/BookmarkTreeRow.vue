<template>
  <div>
    <template v-if="node.type === HomeItemType.BOOKMARK_DIR">
      <div class="flex items-center gap-2 py-1.5" :style="indentStyle">
        <Icon
          icon="mdi:folder"
          class="size-4 shrink-0"
          :class="folderColor ? '' : 'text-amber-500'"
          :style="folderColor ? { color: folderColor } : undefined" />
        <span class="text-sm font-medium text-slate-500 dark:text-slate-400 truncate">{{ node.name || '文件夹' }}</span>
      </div>
      <BookmarkTreeRow
        v-for="child in children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        @edit="(n) => emit('edit', n)" />
    </template>

    <a
      v-else-if="node.type === HomeItemType.BOOKMARK && node.typeApp"
      :href="externalHref(node.typeApp.urlFull)"
      target="_blank"
      rel="noopener noreferrer"
      draggable="false"
      class="group flex items-center gap-2 py-1.5 pr-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors long-press-target"
      :class="{
        'bg-slate-100 dark:bg-slate-800/60': contextMenuNodeId === node.id,
        'long-press-active': longPress.pressingId.value === node.id,
      }"
      :style="bookmarkIndentStyle"
      @click.capture="longPress.guardClick"
      @click="recordOpen(node)"
      @contextmenu.prevent="openMenu($event.x, $event.y, node)"
      @touchstart="(e) => longPress.start(e, node.id, (x, y) => openMenu(x, y, node))"
      @touchmove="longPress.move"
      @touchend="longPress.end">
      <BookmarkLogo :value="node.typeApp" size="S" />
      <span
        class="text-sm truncate"
        :class="
          node.typeApp.isActivity === false
            ? 'text-slate-400 dark:text-slate-500'
            : 'text-slate-700 dark:text-slate-200'
        ">
        {{ node.typeApp.title || node.typeApp.urlBase }}
      </span>
      <!-- 手机端没有右键，且已改由长按触发同一份菜单，故手机端不再渲染这个按钮，
           桌面端仍保留悬停显形的入口 -->
      <button
        v-if="!isMobile"
        type="button"
        class="ml-auto shrink-0 reveal-on-hover p-1 -m-1 text-slate-400 hover:text-primary transition-opacity"
        aria-label="书签操作"
        @click.stop.prevent="openMenuFromButton($event, node)">
        <Icon icon="mdi:dots-horizontal" class="size-4" />
      </button>
    </a>

    <div
      v-else-if="node.type === HomeItemType.BOOKMARK_LOADING"
      class="flex items-center gap-2 py-1.5"
      :style="bookmarkIndentStyle">
      <div class="size-5 rounded-full bg-slate-200 dark:bg-slate-700 animate-pulse shrink-0" />
      <span class="text-sm text-slate-400 dark:text-slate-500 truncate">{{ node.name || '加载中…' }}</span>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, h } from 'vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { Icon } from '@iconify/vue'
import { bookmarksDel, bookmarksPin, bookmarksRecordOpen } from '@api'
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
import { externalHref } from '@utils'
import { displayFolderColor } from '../constants/folder-colors'

defineOptions({ name: 'BookmarkTreeRow' })

const props = defineProps<{ node: UserLayoutNodeVO; depth: number }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO] }>()

const bookmarkStore = useBookmarkStore()
const { moveWithin, moveToFolder, positionOf, hasMoveTargets } = useBookmarkMove()
const isMobile = useIsMobile()
const longPress = useLongPress()
// 全局共享，而不是每个 BookmarkTreeRow 实例各自一份本地 ref：每一行都是独立的组件实例，
// 若各存各的，「长按 A 再长按 B」时 A 的 onClose 永远等不到（同一份菜单单例被 B 直接顶掉），
// A 的高亮就再也没人清得掉。见 composables/useActiveMenuTarget.ts 的注释
const contextMenuNodeId = useActiveMenuTarget()
const folderColor = computed(() => displayFolderColor(props.node.color))
const children = computed(() =>
  props.node.type === HomeItemType.BOOKMARK_DIR ? bookmarkStore.childrenOf(props.node.id) : [],
)
const indentStyle = computed(() => ({ paddingLeft: `${props.depth * 1.25}rem` }))
// 单条书签在文件夹卡片中额外增加左内边距，与文件夹行区分开
const bookmarkIndentStyle = computed(() => ({ paddingLeft: `${props.depth * 1.25 + 0.5}rem` }))

function recordOpen(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  bookmarksRecordOpen(node.typeApp.bookmarkId).catch(() => {})
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
    console.error('[BookmarkTreeRow] 删除书签失败', error)
  }
}

function goSimilar(node: UserLayoutNodeVO) {
  navigateTo({ path: '/bookmark/similar', query: { id: node.id } })
}

async function togglePinned(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  const next = !node.typeApp.pinned
  try {
    await bookmarksPin(node.typeApp.bookmarkId, next)
    bookmarkStore.setPinnedLocal(node.id, next)
    useToastStore().success(next ? '已置顶' : '已取消置顶')
  } catch (error) {
    console.error('[BookmarkTreeRow] 置顶状态切换失败', error)
  }
}

/** 右键与行尾 ⋯ 共用同一份菜单定义 —— 复制一份的话，以后加菜单项必然漏改一处 */
function openMenu(x: number, y: number, node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  contextMenuNodeId.value = node.id
  ContextMenu.showContextMenu({
    items: [
      { label: '修改', icon: h(Icon, { icon: 'mdi:pencil', class: 'size-4' }), onClick: () => emit('edit', node) },
      {
        label: node.typeApp.pinned ? '取消置顶' : '置顶',
        icon: h(Icon, { icon: node.typeApp.pinned ? 'mdi:pin-off' : 'mdi:pin', class: 'size-4' }),
        onClick: () => togglePinned(node),
      },
      {
        label: '更多相似书签',
        icon: h(Icon, { icon: 'mdi:view-grid-outline', class: 'size-4' }),
        onClick: () => goSimilar(node),
      },
      // 以下三项是拖拽在触屏/键盘下的替代路径 —— 拖拽用的是 HTML5 drag-and-drop，
      // 手机上根本不会触发，没有这三项手机用户无法整理书签
      // divided 的布尔值等价于 'down'（分割线画在自己下面），这里要的是画在自己上面，必须显式写 'up'
      { label: '上移', icon: h(Icon, { icon: 'mdi:arrow-up', class: 'size-4' }), divided: 'up', disabled: positionOf(node).isFirst, onClick: () => moveWithin(node, -1) },
      { label: '下移', icon: h(Icon, { icon: 'mdi:arrow-down', class: 'size-4' }), disabled: positionOf(node).isLast, onClick: () => moveWithin(node, 1) },
      {
        label: '移动到…',
        icon: h(Icon, { icon: 'mdi:folder-move-outline', class: 'size-4' }),
        hidden: !hasMoveTargets.value,
        onClick: () => moveToFolder(node),
      },
      {
        label: '删除',
        icon: h(Icon, { icon: 'mdi:trash-can', class: 'size-4' }),
        customClass: 'bookmark-context-menu-delete',
        divided: 'up',
        onClick: () => delOne(node),
      },
    ],
    x,
    y,
    customClass: 'bookmark-context-menu',
    onClose: () => {
      if (contextMenuNodeId.value === node.id) contextMenuNodeId.value = null
    },
  })
}

/** 按钮触发时用按钮自身的位置，而不是点击坐标 —— 后者在触屏上会把菜单顶到手指底下 */
function openMenuFromButton(e: MouseEvent, node: UserLayoutNodeVO) {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  openMenu(rect.right, rect.bottom, node)
}
</script>
