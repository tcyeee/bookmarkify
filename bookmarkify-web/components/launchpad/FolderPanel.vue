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
import { bookmarksRenameDir, bookmarksSort } from '@api'
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
const vuuriOptions = {
  layout: { fillGaps: true, rounding: false },
  layoutDuration: 200,
  showDuration: 100,
  hideDuration: 100,
  dragReleaseDuration: 0,
  dragStartPredicate: { distance: 8, delay: 0 },
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

async function onDragReleaseEnd() {
  dragging.value = false
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
