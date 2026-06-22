<template>
  <Teleport to="body">
    <Transition name="folder-overlay">
      <!-- 整层不拦截指针（dim 层纯视觉），关闭交由捕获阶段 click + Esc；放行背后主网格拖拽 -->
      <div v-if="visible" class="fixed inset-0 z-50 pointer-events-none">
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
            <span
              v-else
              class="text-white text-base font-medium tracking-wide cursor-text hover:opacity-70 transition-opacity"
              title="点击修改名称"
              @click="startEdit">
              {{ folder?.name || '文件夹' }}
            </span>
          </div>
          <ClientOnly>
            <LaunchGrid
              :items="children"
              :parent-key="folder?.id ?? ''"
              :is-folder="true"
              @commit="(c) => emit('commit', c)"
              @show-detail="emit('passShowDetail', $event)" />
          </ClientOnly>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useWindowSize, useEventListener } from '@vueuse/core'
import type { BookmarkShow, UserLayoutNodeVO } from '@typing'
import { bookmarksRenameDir } from '@api'
import LaunchGrid from './LaunchGrid.vue'
import type { DragCommit } from '@/composables/useLaunchpadDrag'

const props = defineProps<{ visible: boolean; folder: UserLayoutNodeVO | null; anchorRect?: DOMRect | null }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'commit', c: DragCommit): void; (e: 'passShowDetail', b: BookmarkShow): void }>()

const bookmarkStore = useBookmarkStore()
const children = computed(() => (props.folder ? bookmarkStore.childrenOf(props.folder.id) : []))
const { width: windowWidth, height: windowHeight } = useWindowSize()
const cardRef = ref<HTMLElement | null>(null)

const cardStyle = computed(() => {
  const w = Math.min(windowWidth.value * 0.55, windowWidth.value - 16)
  const left = Math.max(8, (windowWidth.value - w) / 2)
  const r = props.anchorRect
  const top = r ? Math.min(Math.max(8, r.top - 12), Math.max(8, windowHeight.value * 0.4)) : 80
  return { left: `${left}px`, top: `${top}px`, width: `${w}px` }
})

// 重命名（沿用 FolderPanel 逻辑）
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
    if (bookmarkStore.nodes[props.folder.id]) bookmarkStore.nodes[props.folder.id] = { ...bookmarkStore.nodes[props.folder.id], name }
  } catch {
    // http 层已统一提示
  }
}

// 关闭：捕获阶段 click（卡片外）+ Esc。dim 层 pointer-events-none，故用捕获阶段拦截卡片外点击
useEventListener(
  document,
  'click',
  (e: MouseEvent) => {
    if (!props.visible) return
    const target = e.target as Node | null
    if (cardRef.value && target && cardRef.value.contains(target)) return
    e.stopPropagation()
    e.preventDefault()
    emit('close')
  },
  { capture: true },
)
useEventListener(window, 'keydown', (e: KeyboardEvent) => {
  if (e.key !== 'Escape' || !props.visible || editing.value) return
  emit('close')
})
</script>

<style scoped>
/* 仅淡入淡出：避免 transform 破坏 backdrop-filter 的 dim 层 */
.folder-overlay-enter-active,
.folder-overlay-leave-active {
  transition: opacity 0.2s ease;
}
.folder-overlay-enter-from,
.folder-overlay-leave-to {
  opacity: 0;
}
</style>
