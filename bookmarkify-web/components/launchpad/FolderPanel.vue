<template>
  <Teleport to="body">
    <Transition name="folder-panel">
      <div v-if="visible" class="fixed inset-0 z-50 flex flex-col items-center justify-center" @click.self="close">
        <!-- 背景遮罩 -->
        <div class="absolute inset-0 bg-black/30 backdrop-blur-md" @click="close" />

        <!-- 面板主体 -->
        <div class="relative z-10 w-80 max-w-[90vw] rounded-3xl bg-white/20 backdrop-blur-xl border border-white/30 shadow-2xl p-5">
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
              :title="'点击修改名称'"
              @click="startEdit">
              {{ folder?.name || '文件夹' }}
            </span>
          </div>

          <!-- 书签网格 -->
          <div class="flex flex-wrap justify-center gap-4">
            <div
              v-for="child in children"
              :key="child.id"
              class="group relative flex flex-col items-center cursor-pointer"
              @click="openBookmark(child)">
              <!-- 移出按钮（hover 显示） -->
              <button
                class="absolute -top-1.5 -right-1.5 z-10 size-5 rounded-full bg-black/50 text-white/80 text-[10px] flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity hover:bg-black/80"
                title="移出到桌面"
                @click.stop="moveOut(child)">
                ↩
              </button>
              <BookmarkLogo v-if="child.typeApp" :value="child.typeApp" :size="iconSize" />
              <div
                v-else
                class="rounded-2xl bg-gray-300"
                :style="{ width: `${iconSize}px`, height: `${iconSize}px` }" />
              <span class="mt-1 text-xs text-white/90 truncate text-center" :style="{ width: `${iconSize}px` }">
                {{ child.typeApp?.title || child.typeApp?.urlBase || child.name || '' }}
              </span>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script lang="ts" setup>
import { computed, nextTick, ref, watch } from 'vue'
import { BookmarkOpenMode, HomeItemType, type UserLayoutNodeVO } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'
import { bookmarksRenameDir, bookmarksMoveNode } from '@api'
import BookmarkLogo from './cell/BookmarkLogo.vue'

const props = defineProps<{ visible: boolean; folder: UserLayoutNodeVO | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const preferenceStore = usePreferenceStore()
const bookmarkStore = useBookmarkStore()
const iconSize = computed(() => preferenceStore.bookmarkCellSizePx)
const bookmarkOpenMode = computed(() => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB)

const children = computed(() =>
  (props.folder?.children ?? []).filter((c) => c.type === HomeItemType.BOOKMARK),
)

// ── 重命名 ────────────────────────────────────────────────────────────────────
const editing = ref(false)
const editingName = ref('')
const nameInputRef = ref<HTMLInputElement | null>(null)

// 面板关闭时重置编辑状态
watch(() => props.visible, (v) => { if (!v) editing.value = false })

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
    // 同步更新本地 store
    const node = bookmarkStore.layoutNode?.find((n) => n.id === props.folder!.id)
    if (node) node.name = name
  } catch {
    // 错误已由 http 层统一提示
  }
}

// ── 移出到桌面 ────────────────────────────────────────────────────────────────
async function moveOut(child: UserLayoutNodeVO) {
  if (!props.folder) return
  try {
    const updatedDir = await bookmarksMoveNode(child.id, null)
    // 把节点追加回根列表
    const rootNode = { ...child, parentId: null }
    bookmarkStore.layoutNode = [...(bookmarkStore.layoutNode ?? []), rootNode]
    // 用 API 返回值刷新文件夹 children
    const dirNode = bookmarkStore.layoutNode.find((n) => n.id === updatedDir.id)
    if (dirNode) dirNode.children = updatedDir.children
    ElNotification.success({ message: '已移出到桌面' })
  } catch {
    // 错误已由 http 层统一提示
  }
}

// ── 打开书签 ──────────────────────────────────────────────────────────────────
function openBookmark(child: UserLayoutNodeVO) {
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
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.folder-panel-enter-from,
.folder-panel-leave-to {
  opacity: 0;
  transform: scale(0.95);
}
</style>
