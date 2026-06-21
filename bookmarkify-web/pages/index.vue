<template>
  <div ref="outerRef" class="flex w-full justify-center">
    <ClientOnly>
      <LaunchGrid
        :items="bookmarkStore.rootNodes"
        :parent-key="ROOT_KEY"
        @commit="(c) => handleCommit(ROOT_KEY, c)"
        @open-dir="onOpenDir"
        @show-detail="onShowDetail" />
    </ClientOnly>
  </div>

  <el-dialog v-model="detailVisible" title="书签详情" width="480px" :close-on-click-modal="true">
    <LaunchpadDetail :data="detailBookmark" />
  </el-dialog>

  <FolderOverlay
    :visible="folderVisible"
    :folder="folderNode"
    :anchor-rect="folderAnchorRect"
    @close="folderVisible = false"
    @commit="(c) => handleCommit(folderId ?? '', c)"
    @pass-show-detail="onShowDetail" />
</template>

<script lang="ts" setup>
import { bookmarksSort, bookmarksMoveNode, bookmarksCreateDir } from '@api'
import { ROOT_KEY, type BookmarkShow, type UserLayoutNodeVO } from '@typing'
import type { DragCommit } from '@/composables/useLaunchpadDrag'
import LaunchGrid from '@/components/launchpad/LaunchGrid.vue'
import FolderOverlay from '@/components/launchpad/FolderOverlay.vue'
definePageMeta({ middleware: 'auth', layout: 'launch' })

const bookmarkStore = useBookmarkStore()
const outerRef = ref<HTMLElement | null>(null)

// ── 详情弹窗 ──────────────────────────────────────────────────────────────────
const detailVisible = ref(false)
const detailBookmark = ref<BookmarkShow | null>(null)
function onShowDetail(b: BookmarkShow) {
  detailBookmark.value = b
  detailVisible.value = true
}

// ── 文件夹浮层 ────────────────────────────────────────────────────────────────
const folderVisible = ref(false)
const folderId = ref<string | null>(null)
const folderAnchorRect = ref<DOMRect | null>(null)
const folderNode = computed(() => (folderId.value ? bookmarkStore.rootNodes.find((n) => n.id === folderId.value) ?? null : null))
function onOpenDir(item: UserLayoutNodeVO) {
  const el = document.querySelector(`[data-folder-anchor="${item.id}"]`) as HTMLElement | null
  folderAnchorRect.value = el ? el.getBoundingClientRect() : null
  folderId.value = item.id
  folderVisible.value = true
}

// ── 统一持久化 ────────────────────────────────────────────────────────────────
/** 把某父级的当前顺序落地到后端 */
function persistOrder(parentKey: string) {
  const ids = bookmarkStore.order[parentKey] ?? []
  const params: Record<string, number> = {}
  ids.forEach((id, i) => (params[id] = i))
  bookmarksSort(params)
}

/** 拖拽提交的唯一入口：根据动作类型本地更新 + 持久化 */
async function handleCommit(parentKey: string, c: DragCommit) {
  if (c.kind === 'none') return

  if (c.kind === 'reorder') {
    bookmarkStore.reorderLocal(parentKey, c.ids) // 乐观本地
    persistOrder(parentKey)
    return
  }

  if (c.kind === 'merge') {
    try {
      const folder = await bookmarksCreateDir([c.draggedId, c.targetId], '新建文件夹', c.index)
      bookmarkStore.createFolderLocal(folder, c.draggedId, c.targetId, c.index)
      ElNotification.success({ message: '已创建文件夹' })
    } catch {
      // http 层已统一提示
    }
    return
  }

  if (c.kind === 'moveInto') {
    try {
      await bookmarksMoveNode(c.draggedId, c.folderId)
      bookmarkStore.moveLocal(c.draggedId, c.folderId, bookmarkStore.childrenOf(c.folderId).length)
      ElNotification.success({ message: '已移入文件夹' })
    } catch {
      // http 层已统一提示
    }
    return
  }

  if (c.kind === 'eject') {
    // parentKey 为来源文件夹 id；拖出到根
    try {
      const result = await bookmarksMoveNode(c.draggedId, null)
      bookmarkStore.moveLocal(c.draggedId, ROOT_KEY, (bookmarkStore.order[ROOT_KEY] ?? []).length)
      const dissolved = bookmarkStore.applyMoveResult(result, parentKey)
      persistOrder(ROOT_KEY)
      if (dissolved || bookmarkStore.childrenOf(parentKey).length === 0) folderVisible.value = false
    } catch {
      // http 层已统一提示
    }
    return
  }
}
</script>

<style>
/* 合并目标：图标白色外边框 + 缓慢闪烁（命中文件夹 .folder-icon 或书签 .overflow-hidden） */
.merge-glow-host :is(.folder-icon, .overflow-hidden) {
  box-shadow:
    0 0 0 3px rgba(255, 255, 255, 0.95),
    0 0 16px rgba(255, 255, 255, 0.4) !important;
  animation: merge-blink 700ms ease-in-out infinite !important;
}

@keyframes merge-blink {
  0%,
  100% {
    box-shadow:
      0 0 0 3px rgba(255, 255, 255, 0.95),
      0 0 16px rgba(255, 255, 255, 0.4);
  }
  50% {
    box-shadow:
      0 0 0 3px rgba(255, 255, 255, 0.3),
      0 0 6px rgba(255, 255, 255, 0.15);
  }
}
</style>
