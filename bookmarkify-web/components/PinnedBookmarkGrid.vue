<template>
  <div class="flex flex-wrap gap-4">
    <a
      v-for="node in nodes"
      :key="node.id"
      :href="externalHref(node.typeApp!.urlFull)"
      target="_blank"
      rel="noopener noreferrer"
      class="group relative w-16 flex flex-col items-center gap-1 rounded-lg p-1.5 hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
      @click="recordOpen(node)"
      @contextmenu.prevent="openMenu($event.x, $event.y, node)">
      <BookmarkLogo :value="node.typeApp!" :size="56" :prefer-hd="true" />
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
        {{ node.typeApp!.title || node.typeApp!.urlBase }}
      </span>
    </a>
  </div>
</template>

<script lang="ts" setup>
import { h } from 'vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { Icon } from '@iconify/vue'
import { bookmarksDel, bookmarksPin, bookmarksRecordOpen } from '@api'
import type { UserLayoutNodeVO } from '@typing'
import { externalHref } from '@utils'
import BookmarkLogo from '@/components/launchpad/cell/BookmarkLogo.vue'

defineOptions({ name: 'PinnedBookmarkGrid' })

defineProps<{ nodes: UserLayoutNodeVO[] }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO] }>()

const bookmarkStore = useBookmarkStore()

function recordOpen(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  bookmarksRecordOpen(node.typeApp.bookmarkUserLinkId).catch(() => {})
}

async function unpinOne(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  try {
    await bookmarksPin(node.typeApp.bookmarkUserLinkId, false)
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
  ContextMenu.showContextMenu({
    items: [
      { label: '取消置顶', icon: h(Icon, { icon: 'mdi:pin-off', class: 'size-4' }), onClick: () => unpinOne(node) },
      { label: '修改', icon: h(Icon, { icon: 'mdi:pencil', class: 'size-4' }), onClick: () => emit('edit', node) },
      { label: '删除', icon: h(Icon, { icon: 'mdi:trash-can', class: 'size-4' }), onClick: () => delOne(node) },
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
