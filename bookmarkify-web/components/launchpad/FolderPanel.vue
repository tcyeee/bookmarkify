<template>
  <Teleport to="body">
    <Transition name="folder-panel">
      <div v-if="visible" class="fixed inset-0 z-50 flex flex-col items-center justify-center" @click.self="close">
        <!-- 背景遮罩 -->
        <div class="absolute inset-0 bg-black/30 backdrop-blur-md" @click="close" />

        <!-- 面板主体 -->
        <div class="relative z-10 w-[70vw] rounded-3xl bg-white/20 backdrop-blur-xl border border-white/30 shadow-2xl p-5">
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
                    <!-- 移出按钮（hover 显示，拖拽中隐藏） -->
                    <button
                      v-show="!dragging"
                      class="absolute -top-1.5 -right-1.5 z-10 size-5 rounded-full bg-black/50 text-white/80 text-[10px] flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity hover:bg-black/80"
                      title="移出到桌面"
                      @click.stop="moveOut(item)">
                      ↩
                    </button>
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
import { useWindowSize } from '@vueuse/core'
import { BookmarkOpenMode, HomeItemType, type UserLayoutNodeVO } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'
import { bookmarksRenameDir, bookmarksMoveNode, bookmarksSort } from '@api'
import BookmarkLogo from './cell/BookmarkLogo.vue'

const props = defineProps<{ visible: boolean; folder: UserLayoutNodeVO | null }>()
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
const { width: windowWidth } = useWindowSize()
const PANEL_CONTENT_WIDTH = computed(() => windowWidth.value * 0.7 - 40) // 70vw - p-5*2

const columnCount = computed(() => Math.max(1, Math.floor((PANEL_CONTENT_WIDTH.value + ITEM_GAP) / ITEM_WIDTH.value)))
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

// ── 移出到桌面 ────────────────────────────────────────────────────────────────
async function moveOut(child: UserLayoutNodeVO) {
  if (!props.folder) return
  const folderId = props.folder.id
  try {
    const result = await bookmarksMoveNode(child.id, null)
    const movedNode = { ...child, parentId: null }

    if (result.type === HomeItemType.BOOKMARK_DIR) {
      // 文件夹仍存在：从本地列表移除，刷新 store children
      localChildren.value = localChildren.value.filter((c) => c.id !== child.id)
      bookmarkStore.layoutNode = [...(bookmarkStore.layoutNode ?? []), movedNode]
      const dirNode = bookmarkStore.layoutNode.find((n) => n.id === result.id)
      if (dirNode) dirNode.children = result.children
    } else {
      // 文件夹已被自动解散
      bookmarkStore.layoutNode = [
        ...(bookmarkStore.layoutNode ?? []).filter((n) => n.id !== folderId),
        movedNode,
        { ...result, parentId: null },
      ]
      emit('close')
    }
    ElNotification.success({ message: '已移出到桌面' })
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
</script>

<style scoped>
.folder-panel-enter-active,
.folder-panel-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.folder-panel-enter-from,
.folder-panel-leave-to {
  opacity: 0;
  transform: scale(0.95);
}

.folder-grid .muuri-item {
  margin: 0;
}

.folder-grid .muuri-item-content {
  width: 100%;
  height: 100%;
  display: flex;
  justify-content: center;
  align-items: center;
  box-sizing: border-box;
}
</style>
