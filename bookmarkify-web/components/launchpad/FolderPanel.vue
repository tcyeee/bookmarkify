<template>
  <Teleport to="body">
    <Transition name="folder-panel">
      <!-- 整层不拦截指针（dim 层纯视觉），以便把图标拖拽到背后主网格；关闭交由捕获阶段 click 拦截 + Esc -->
      <div v-if="visible" class="fixed inset-0 z-50 pointer-events-none">
        <!-- dim/blur 视觉层：不拦截指针、不做位移变换（避免 transform 破坏 backdrop-filter）-->
        <div class="absolute inset-0 bg-black/30 backdrop-blur-md" />

        <!-- 卡片：原位定位，可交互 -->
        <div ref="cardRef" class="absolute z-10 pointer-events-auto rounded-3xl bg-white/20 border border-white/30 shadow-2xl p-5" :style="cardStyle">
          <!-- 文件夹名称（点击进入编辑）-->
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
            <span
              v-else
              class="text-white text-base font-medium tracking-wide cursor-text hover:opacity-70 transition-opacity"
              title="点击修改名称"
              @click="startEdit">
              {{ folder?.name || '文件夹' }}
            </span>
          </div>

          <!-- 书签网格（Vuuri 拖拽排序） -->
          <div class="flex justify-center">
            <ClientOnly>
              <Vuuri
                :key="vuuriKey"
                group-id="launchpad"
                class="folder-grid"
                :style="vuuriStyle"
                :model-value="localChildren"
                item-key="id"
                :options="vuuriOptions"
                :drag-enabled="true"
                :get-item-width="() => `${ITEM_WIDTH}px`"
                :get-item-height="() => `${ITEM_HEIGHT}px`"
                @input="onGridInput"
                @drag-start="dragging = true"
                @receive="onFolderReceive"
                @drag-release-end="onDragReleaseEnd">
                <template #item="{ item }">
                  <div
                    class="group relative flex flex-col items-center cursor-pointer"
                    :style="{ width: `${iconSize}px` }"
                    @click="onItemClick(item)">
                    <BookmarkLogo v-if="item.typeApp" :value="item.typeApp" :size="iconSize" />
                    <div v-else class="rounded-2xl bg-gray-300" :style="{ width: `${iconSize}px`, height: `${iconSize}px` }" />
                    <span
                      v-if="showTitle"
                      class="mt-1 text-xs text-white/90 truncate text-center"
                      :style="{ width: `${iconSize}px` }">
                      {{ item.typeApp?.title || item.typeApp?.urlBase || item.name || '' }}
                    </span>
                  </div>
                </template>
              </Vuuri>
            </ClientOnly>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script lang="ts" setup>
import { computed, defineAsyncComponent, defineComponent, nextTick, ref, watch } from 'vue'
import { useWindowSize, useEventListener } from '@vueuse/core'
import { BookmarkOpenMode, HomeItemType, type UserLayoutNodeVO } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'
import { bookmarksRenameDir, bookmarksSort, bookmarksMoveNode } from '@api'
import BookmarkLogo from './cell/BookmarkLogo.vue'

const props = defineProps<{ visible: boolean; folder: UserLayoutNodeVO | null; anchorRect?: DOMRect | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const preferenceStore = usePreferenceStore()
const bookmarkStore = useBookmarkStore()
const iconSize = computed(() => preferenceStore.bookmarkCellSizePx)
const showTitle = computed(() => preferenceStore.preference?.showTitle ?? true)
const bookmarkOpenMode = computed(() => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB)

/** 客户端按需加载 Vuuri */
const Vuuri = import.meta.client
  ? defineAsyncComponent(() => import('vuuri'))
  : defineComponent({ name: 'VuuriPlaceholder', setup: () => () => null })

// ── 拖拽排序 ──────────────────────────────────────────────────────────────────
const ITEM_GAP = 16
const ITEM_WIDTH = computed(() => iconSize.value + ITEM_GAP)
const ITEM_HEIGHT = computed(() => iconSize.value + ITEM_GAP + (showTitle.value ? 28 : 0))
const { width: windowWidth, height: windowHeight } = useWindowSize()

// 卡片宽度固定为视口 55%（随窗口等比缩放）；列数 = 该宽度（扣除 p-5 内边距）内能容纳的格子数
const CARD_WIDTH = computed(() => Math.min(windowWidth.value * 0.55, windowWidth.value - 16))
const columnCount = computed(() => Math.max(1, Math.floor((CARD_WIDTH.value - 40 + ITEM_GAP) / ITEM_WIDTH.value)))

/**
 * 卡片定位：大卡片（55vw）水平居中，垂直跟随点击行并夹紧到视口上半部。
 * 关键：不能加 overflow/maxHeight —— 否则会裁剪"拖出卡片"的图标（见 自定义拖拽踩坑合集.md #4 #5）。
 */
const cardStyle = computed(() => {
  const w = CARD_WIDTH.value
  const left = Math.max(8, (windowWidth.value - w) / 2)
  const r = props.anchorRect
  const top = r ? Math.min(Math.max(8, r.top - 12), Math.max(8, windowHeight.value * 0.4)) : 80
  return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
})
const vuuriStyle = computed(() => ({
  width: `${columnCount.value * ITEM_WIDTH.value}px`,
}))
// 滞回边距：进入文件夹用整张卡片（易进）；移出要求中心超出卡片 + 该边距（进去就粘住，抖动不弹出）
const EJECT_MARGIN = 40

const vuuriOptions = {
  layout: { fillGaps: true, rounding: false },
  layoutDuration: 200,
  showDuration: 100,
  hideDuration: 100,
  dragReleaseDuration: 0,
  dragStartPredicate: { distance: 8, delay: 0 },
  /**
   * 自定义排序：被拖项中心离开卡片范围（任意方向，含下方空白）→ 移到主网格末尾；
   * 仍在卡片内 → 按重叠面积在文件夹内重排。不依赖"与主网格图标重叠"，解决向下拖不出的问题。
   */
  dragSortPredicate: (item: any) => {
    const dragEl = item.getElement?.() as HTMLElement | undefined
    if (!dragEl) return false
    const dr = dragEl.getBoundingClientRect()
    const cx = dr.left + dr.width / 2
    const cy = dr.top + dr.height / 2

    const cardEl = cardRef.value
    if (cardEl) {
      const cr = cardEl.getBoundingClientRect()
      const outside =
        cx < cr.left - EJECT_MARGIN || cx > cr.right + EJECT_MARGIN || cy < cr.top - EJECT_MARGIN || cy > cr.bottom + EJECT_MARGIN
      if (outside) {
        const grids: any[] = item.getGrid?.()?._settings?.dragSort?.() ?? []
        const mainGrid = grids.find((g) => g !== item.getGrid?.() && g?.getElement?.()?.classList?.contains('demo-grid'))
        if (mainGrid) return { grid: mainGrid, index: mainGrid.getItems().length, action: 'move' }
        return false
      }
    }

    // 卡片内：与重叠面积最大的同网格项交换
    const items: any[] = item.getGrid?.()?.getItems?.() ?? []
    let bestArea = 0
    let bestIndex = -1
    items.forEach((t: any, idx: number) => {
      if (t === item || !t.isActive?.()) return
      const el = t.getElement?.() as HTMLElement | undefined
      if (!el) return
      const r = el.getBoundingClientRect()
      const ox = Math.min(dr.right, r.right) - Math.max(dr.left, r.left)
      const oy = Math.min(dr.bottom, r.bottom) - Math.max(dr.top, r.top)
      if (ox <= 0 || oy <= 0) return
      const area = ox * oy
      if (area > bestArea) {
        bestArea = area
        bestIndex = idx
      }
    })
    return bestIndex >= 0 ? { index: bestIndex, action: 'move' } : false
  },
}

const cardRef = ref<HTMLElement | null>(null)
const localChildren = ref<UserLayoutNodeVO[]>([])
const vuuriKey = ref(0)
const dragging = ref(false)
let pendingSort = false

// 面板打开时初始化本地列表，文件夹切换时重置
watch(
  () => [props.visible, props.folder?.id] as const,
  ([visible]) => {
    editing.value = false
    dragging.value = false
    pendingSort = false
    if (visible && props.folder) {
      localChildren.value = (props.folder.children || []).filter((c) => c.type === HomeItemType.BOOKMARK)
      vuuriKey.value++
    }
  },
)

function onGridInput(list: UserLayoutNodeVO[]) {
  localChildren.value = list
  if (dragging.value) pendingSort = true
}

// 反向拖入：主网格图标进入本文件夹（vuuri group 的 receive），记录待持久化的 id
const folderReceivedId = ref<string | null>(null)
function onFolderReceive(e: any) {
  folderReceivedId.value = (e?.item?.getElement?.() as HTMLElement | undefined)?.dataset?.itemKey ?? null
}

async function onDragReleaseEnd() {
  dragging.value = false

  // 反向拖入：主网格图标落入本文件夹 → 改归属到本文件夹 + 重排 children
  const receivedId = folderReceivedId.value
  folderReceivedId.value = null
  if (receivedId && props.folder) {
    try {
      await bookmarksMoveNode(receivedId, props.folder.id)
    } catch {
      // 错误已由 http 层统一提示
    }
    const params: Record<string, number> = {}
    localChildren.value.forEach((node, index) => {
      params[node.id] = index
    })
    bookmarksSort(params)
    const dir = bookmarkStore.layoutNode?.find((n) => n.id === props.folder?.id)
    if (dir) dir.children = [...localChildren.value]
    return
  }

  // 文件夹内部重排
  if (!pendingSort) return
  pendingSort = false
  const params: Record<string, number> = {}
  localChildren.value.forEach((node, index) => {
    params[node.id] = index
  })
  bookmarksSort(params)
  // 同步更新 store 中文件夹的 children 顺序
  const dirNode = bookmarkStore.layoutNode?.find((n) => n.id === props.folder?.id)
  if (dirNode) dirNode.children = [...localChildren.value]
}

// ── 重命名 ────────────────────────────────────────────────────────────────────
const editing = ref(false)
const editingName = ref('')
const nameInputRef = ref<HTMLInputElement | null>(null)

function startEdit() {
  editingName.value = props.folder?.name ?? ''
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
  if (!name || !props.folder || name === props.folder.name) return

  try {
    await bookmarksRenameDir(props.folder.id, name)
    const node = bookmarkStore.layoutNode?.find((n) => n.id === props.folder!.id)
    if (node) node.name = name
  } catch {
    // 错误已由 http 层统一提示
  }
}

// ── 打开书签 ──────────────────────────────────────────────────────────────────
function onItemClick(child: UserLayoutNodeVO) {
  if (dragging.value) return
  if (!child.typeApp?.urlFull) return
  const target = bookmarkOpenMode.value === BookmarkOpenMode.NEW_TAB ? '_blank' : '_self'
  window.open(child.typeApp.urlFull, target)
}

function close() {
  emit('close')
}

// ── 关闭面板：卡片外点击（捕获阶段拦截，阻止穿透到主网格）+ 全局 Esc ──────────
// dim 层为 pointer-events-none 以放行拖拽，故用捕获阶段 click 拦截卡片外点击：
// 既关闭文件夹，又阻止该点击落到主网格触发打开书签等；拖拽以 mousedown 触发，不受 click 拦截影响
useEventListener(
  document,
  'click',
  (e: MouseEvent) => {
    if (!props.visible || dragging.value) return
    const target = e.target as Node | null
    if (cardRef.value && target && cardRef.value.contains(target)) return // 卡片内点击放行
    e.stopPropagation()
    e.preventDefault()
    close()
  },
  { capture: true },
)
// useEventListener 在组件卸载时自动解绑，无需 onUnmounted 手动清理
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if (e.key !== 'Escape' || !props.visible) return
  if (editing.value) return // 重命名编辑中，交由输入框的 @keydown.esc 先取消编辑
  close()
})
</script>

<style scoped>
/* 仅淡入淡出：避免 transform 作用于 backdrop-filter 的 dim 层导致渲染异常 */
.folder-panel-enter-active,
.folder-panel-leave-active {
  transition: opacity 0.2s ease;
}
.folder-panel-enter-from,
.folder-panel-leave-to {
  opacity: 0;
}

/* Muuri 动态创建的节点无组件 scope 属性，必须用 :deep() 才能命中 */
.folder-grid :deep(.muuri-item) {
  margin: 0;
}

.folder-grid :deep(.muuri-item-content) {
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
  box-sizing: border-box;
}
</style>
