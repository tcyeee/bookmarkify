<template>
  <div class="flex w-full justify-center">
    <ClientOnly>
      <LaunchBoard
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

  <!-- 文件夹浮层 -->
  <Teleport to="body">
    <Transition name="overlay">
      <div v-if="folderVisible" class="fixed inset-0 z-50 pointer-events-none">
        <div class="absolute inset-0 bg-black/30 backdrop-blur-md" />
        <div ref="cardRef" class="absolute z-10 pointer-events-auto rounded-3xl bg-white/20 border border-white/30 shadow-2xl p-5" :style="cardStyle">
          <div class="mb-4 flex justify-center">
            <input
              v-if="editing"
              ref="nameInputRef"
              v-model="editingName"
              class="bg-white/20 border border-white/40 rounded-lg px-3 py-1 text-white text-base font-medium text-center outline-none focus:border-white/70 w-full max-w-[200px]"
              maxlength="30"
              @keydown.enter="submitRename"
              @keydown.esc="cancelEdit"
              @blur="submitRename" />
            <span v-else class="text-white text-base font-medium tracking-wide cursor-text hover:opacity-70" title="点击修改名称" @click="startEdit">
              {{ folderNode?.name || '文件夹' }}
            </span>
          </div>
          <ClientOnly>
            <LaunchBoard
              :items="folderChildren"
              :parent-key="folderId ?? ''"
              :is-folder="true"
              @commit="(c) => handleCommit(folderId ?? '', c)"
              @show-detail="onShowDetail" />
          </ClientOnly>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script lang="ts" setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useWindowSize, useEventListener } from '@vueuse/core'
import { monitorForElements, dropTargetForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { bookmarksSort, bookmarksMoveNode, bookmarksCreateDir, bookmarksRenameDir } from '@api'
import { ROOT_KEY, type BookmarkShow, type UserLayoutNodeVO } from '@typing'
import LaunchBoard, { type DragCommit } from '@/components/launchpad/LaunchBoard.vue'
definePageMeta({ middleware: 'auth', layout: 'launch' })

const bookmarkStore = useBookmarkStore()
const clog = (...a: unknown[]) => console.log('%c[commit]', 'color:#9333ea;font-weight:bold', ...a)

// ── 详情弹窗 ──
const detailVisible = ref(false)
const detailBookmark = ref<BookmarkShow | null>(null)
function onShowDetail(b: BookmarkShow) {
  detailBookmark.value = b
  detailVisible.value = true
}

// ── 文件夹浮层 ──
const { width: windowWidth, height: windowHeight } = useWindowSize()
const folderVisible = ref(false)
const folderId = ref<string | null>(null)
const folderAnchorRect = ref<DOMRect | null>(null)
const cardRef = ref<HTMLElement | null>(null)
const folderNode = computed(() => (folderId.value ? bookmarkStore.rootNodes.find((n) => n.id === folderId.value) ?? null : null))
const folderChildren = computed(() => (folderId.value ? bookmarkStore.childrenOf(folderId.value) : []))

const cardStyle = computed(() => {
  const w = Math.min(windowWidth.value * 0.55, windowWidth.value - 16)
  const left = Math.max(8, (windowWidth.value - w) / 2)
  const r = folderAnchorRect.value
  const top = r ? Math.min(Math.max(8, r.top - 12), Math.max(8, windowHeight.value * 0.4)) : 80
  return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
})

function onOpenDir(item: UserLayoutNodeVO) {
  const el = document.querySelector(`[data-cell-id="${item.id}"]`) as HTMLElement | null
  folderAnchorRect.value = el ? el.getBoundingClientRect() : null
  folderId.value = item.id
  folderVisible.value = true
}

// 关闭：卡片外点击（捕获阶段）+ Esc
useEventListener(
  document,
  'click',
  (e: MouseEvent) => {
    if (!folderVisible.value) return
    const t = e.target as Node | null
    if (cardRef.value && t && cardRef.value.contains(t)) return
    e.stopPropagation()
    e.preventDefault()
    folderVisible.value = false
  },
  { capture: true },
)
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if (e.key === 'Escape' && folderVisible.value && !editing.value) folderVisible.value = false
})

// ── 重命名 ──
const editing = ref(false)
const editingName = ref('')
const nameInputRef = ref<HTMLInputElement | null>(null)
function startEdit() {
  editingName.value = folderNode.value?.name ?? ''
  editing.value = true
  nextTick(() => nameInputRef.value?.select())
}
function cancelEdit() {
  editing.value = false
}
async function submitRename() {
  if (!editing.value) return
  editing.value = false
  const name = editingName.value.trim()
  const fid = folderId.value
  if (!name || !fid || name === folderNode.value?.name) return
  try {
    await bookmarksRenameDir(fid, name)
    if (bookmarkStore.nodes[fid]) bookmarkStore.nodes[fid] = { ...bookmarkStore.nodes[fid], name }
  } catch {
    // http 层已提示
  }
}

// ── 跨容器拖入/拖出（阶段2）──────────────────────────────────────────────────
// 把卡片整体设为 drop 区；页面级 monitor 据「源所在父级 + 是否落在卡片内」判定移入/弹出。
// 各 board 自身的 monitor 仅处理容器内（源在自己 items），跨容器交给这里，互不冲突。
let cardDropCleanup: (() => void) | null = null
let monitorCleanup: (() => void) | null = null

function registerCardDrop() {
  cardDropCleanup?.()
  if (!cardRef.value) return
  cardDropCleanup = dropTargetForElements({
    element: cardRef.value,
    getData: () => ({ zone: 'folder-card', folderId: folderId.value }),
  })
}

watch(folderVisible, (open) => {
  if (open) nextTick(registerCardDrop)
  else cardDropCleanup?.()
})

onMounted(() => {
  monitorCleanup = monitorForElements({
    onDrag: ({ source, location }) => {
      // 仿 macOS：文件夹内图标一旦被拖出卡片范围，立即关闭浮层（drop 仍会完成 eject）
      if (!folderVisible.value || !folderId.value) return
      if (bookmarkStore.parentKeyOf(String(source.data.id)) !== folderId.value) return
      const overCard = location.current.dropTargets.some((t) => t.data.zone === 'folder-card')
      if (!overCard) folderVisible.value = false
    },
    onDrop: ({ source, location }) => {
      const fid = folderId.value
      if (!fid) return
      const src = String(source.data.id)
      const srcParent = bookmarkStore.parentKeyOf(src)
      const overCard = location.current.dropTargets.some((t) => t.data.zone === 'folder-card')
      if (srcParent === ROOT_KEY && overCard && folderVisible.value) {
        // 反向拖入：根图标落入打开的浮层
        handleCommit(fid, { kind: 'moveInto', draggedId: src, folderId: fid })
      } else if (srcParent === fid && !overCard) {
        // 拖出：子项落在卡片外 → 回到根（即使浮层已因拖出而提前关闭）
        handleCommit(fid, { kind: 'eject', draggedId: src })
      }
    },
  })
})
onBeforeUnmount(() => {
  monitorCleanup?.()
  cardDropCleanup?.()
})

// ── 持久化某父级顺序 ──
function persistOrder(parentKey: string) {
  const ids = bookmarkStore.order[parentKey] ?? []
  const params: Record<string, number> = {}
  ids.forEach((id, i) => (params[id] = i))
  bookmarksSort(params)
}

// ── 拖拽提交唯一入口 ──
async function handleCommit(parentKey: string, c: DragCommit) {
  clog('收到', { parentKey, c })
  if (c.kind === 'reorder') {
    bookmarkStore.reorderLocal(parentKey, c.ids)
    persistOrder(parentKey)
    return
  }
  if (c.kind === 'merge') {
    try {
      const folder = await bookmarksCreateDir([c.draggedId, c.targetId], '新建文件夹', c.index)
      bookmarkStore.createFolderLocal(folder, c.draggedId, c.targetId, c.index)
      ElNotification.success({ message: '已创建文件夹' })
    } catch (e) {
      clog('merge 失败', e)
    }
    return
  }
  if (c.kind === 'moveInto') {
    try {
      await bookmarksMoveNode(c.draggedId, c.folderId)
      bookmarkStore.moveLocal(c.draggedId, c.folderId, bookmarkStore.childrenOf(c.folderId).length)
      ElNotification.success({ message: '已移入文件夹' })
    } catch (e) {
      clog('moveInto 失败', e)
    }
    return
  }
  if (c.kind === 'eject') {
    try {
      const result = await bookmarksMoveNode(c.draggedId, null)
      bookmarkStore.moveLocal(c.draggedId, ROOT_KEY, (bookmarkStore.order[ROOT_KEY] ?? []).length)
      const dissolved = bookmarkStore.applyMoveResult(result, parentKey)
      persistOrder(ROOT_KEY)
      if (dissolved || bookmarkStore.childrenOf(parentKey).length === 0) folderVisible.value = false
    } catch (e) {
      clog('eject 失败', e)
    }
    return
  }
}
</script>

<style>
.overlay-enter-active,
.overlay-leave-active {
  transition: opacity 0.2s ease;
}
.overlay-enter-from,
.overlay-leave-to {
  opacity: 0;
}

/* 合并目标高亮（命中文件夹 .folder-icon 或书签 .overflow-hidden）*/
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
