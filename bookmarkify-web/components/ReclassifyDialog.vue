<template>
  <dialog ref="dialogRef" class="cy-modal" @close="onNativeClose">
    <div class="cy-modal-box max-w-4xl">
      <div class="mb-4 flex items-center gap-2">
        <Icon icon="mdi:auto-fix" class="size-5 text-primary" />
        <span class="text-lg font-semibold text-slate-900 dark:text-slate-100">
          重新归类「{{ folderName }}」
        </span>
      </div>

      <!-- 分析中 -->
      <div v-if="loading" class="flex flex-col items-center gap-3 py-16 text-slate-500 dark:text-slate-400">
        <Icon icon="mdi:loading" class="size-8 animate-spin text-primary" />
        <span class="text-sm">正在让 AI 分析文件夹里的书签，请稍候…</span>
      </div>

      <!-- 出错 / 无结果 -->
      <div v-else-if="errorText" class="py-14 text-center text-sm text-slate-500 dark:text-slate-400">
        {{ errorText }}
      </div>

      <!-- 无需调整：AI 把所有书签都留在原文件夹 -->
      <div
        v-else-if="movableGroups.length === 0"
        class="py-14 text-center text-sm text-slate-500 dark:text-slate-400">
        AI 认为这个文件夹的书签已经归好了，没有需要移动的。
      </div>

      <!-- 看板 -->
      <template v-else>
        <p class="mb-3 text-xs text-slate-400 dark:text-slate-500">
          勾选要执行的分组，确认后书签会移入对应文件夹（标「新建」的会自动创建）。
          <span v-if="willEmptySource" class="text-amber-600 dark:text-amber-500">
            按当前勾选，原文件夹「{{ folderName }}」会被清空并移除。
          </span>
        </p>

        <div class="flex gap-3 overflow-x-auto pb-2">
          <!-- 保留在原文件夹 -->
          <section
            v-if="keepGroup && keepGroup.nodeIds.length"
            class="w-56 shrink-0 rounded-lg border border-dashed border-slate-200 bg-slate-50/60 dark:border-slate-700 dark:bg-slate-800/30">
            <header class="flex items-center gap-2 border-b border-slate-200 px-3 py-2 dark:border-slate-700">
              <span class="text-sm font-medium text-slate-500 dark:text-slate-400 truncate flex-1">
                {{ folderName }}
              </span>
              <span class="cy-badge cy-badge-sm cy-badge-ghost shrink-0">保留</span>
              <span class="text-xs text-slate-400">{{ keepGroup.nodeIds.length }}</span>
            </header>
            <ul class="max-h-72 space-y-1 overflow-y-auto p-2">
              <li v-for="id in keepGroup.nodeIds" :key="id" class="flex items-center gap-2">
                <BookmarkLogo v-if="showOf(id)" :value="showOf(id)!" size="S" />
                <span class="truncate text-xs text-slate-500 dark:text-slate-400">{{ titleOf(id) }}</span>
              </li>
            </ul>
          </section>

          <!-- 会移动的分组 -->
          <section
            v-for="(group, i) in movableGroups"
            :key="i"
            class="w-56 shrink-0 rounded-lg border transition-colors"
            :class="
              enabled[keyOf(group)]
                ? 'border-primary/50 bg-primary/5'
                : 'border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-800/40'
            ">
            <header class="flex items-center gap-2 border-b border-slate-200 px-3 py-2 dark:border-slate-700">
              <input
                type="checkbox"
                class="cy-checkbox cy-checkbox-sm shrink-0"
                :checked="enabled[keyOf(group)]"
                @change="enabled[keyOf(group)] = !enabled[keyOf(group)]" />
              <span class="text-sm font-medium text-slate-700 dark:text-slate-200 truncate flex-1">
                {{ group.folderName }}
              </span>
              <span
                class="cy-badge cy-badge-sm shrink-0"
                :class="group.isNew ? 'cy-badge-primary' : 'cy-badge-ghost'">
                {{ group.isNew ? '新建' : '已有' }}
              </span>
              <span class="text-xs text-slate-400">{{ group.nodeIds.length }}</span>
            </header>
            <ul class="max-h-72 space-y-1 overflow-y-auto p-2">
              <li v-for="id in group.nodeIds" :key="id" class="flex items-center gap-2">
                <BookmarkLogo v-if="showOf(id)" :value="showOf(id)!" size="S" />
                <span class="truncate text-xs text-slate-600 dark:text-slate-300">{{ titleOf(id) }}</span>
              </li>
            </ul>
          </section>
        </div>
      </template>

      <div class="cy-modal-action">
        <button type="button" class="cy-btn cy-btn-ghost" :disabled="applying" @click="close">
          {{ canApply ? '放弃' : '关闭' }}
        </button>
        <button
          v-if="canApply"
          type="button"
          class="cy-btn cy-btn-primary"
          :disabled="applying || selectedCount === 0"
          @click="apply">
          {{ applying ? '归类中…' : `确认归类（${selectedFolderCount} 个文件夹 · ${selectedCount} 条）` }}
        </button>
      </div>
    </div>
    <form method="dialog" class="cy-modal-backdrop">
      <button>close</button>
    </form>
  </dialog>
</template>

<script lang="ts" setup>
import { Icon } from '@iconify/vue'
import { bookmarksReclassifyPlan, bookmarksReclassifyApply } from '@api'
import type { BookmarkShow, ReclassifyGroup, ReclassifyPlan } from '@typing'

const props = defineProps<{ open: boolean; folderId: string; folderName: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean] }>()

const bookmarkStore = useBookmarkStore()

const dialogRef = ref<HTMLDialogElement | null>(null)
const loading = ref(false)
const applying = ref(false)
const errorText = ref('')
const plan = ref<ReclassifyPlan | null>(null)
// 每个「会移动」的分组是否勾选，默认全选
const enabled = ref<Record<string, boolean>>({})

const keyOf = (group: ReclassifyGroup) => `${group.folderName}::${group.folderId ?? ''}`

const keepGroup = computed(() => plan.value?.groups.find((g) => g.keep) ?? null)
const movableGroups = computed(() => plan.value?.groups.filter((g) => !g.keep && g.nodeIds.length) ?? [])
const canApply = computed(() => !loading.value && !errorText.value && movableGroups.value.length > 0)

const selectedGroups = computed(() => movableGroups.value.filter((g) => enabled.value[keyOf(g)]))
const selectedFolderCount = computed(() => selectedGroups.value.length)
const selectedCount = computed(() => selectedGroups.value.reduce((n, g) => n + g.nodeIds.length, 0))

// 勾选的分组移走后，原文件夹是否只剩 ≤1 项（后端会就地解散）
const willEmptySource = computed(() => {
  const total = plan.value?.groups.reduce((n, g) => n + g.nodeIds.length, 0) ?? 0
  return total - selectedCount.value <= 1
})

function showOf(nodeId: string): BookmarkShow | null {
  return bookmarkStore.nodes[nodeId]?.typeApp ?? null
}
function titleOf(nodeId: string): string {
  const s = showOf(nodeId)
  return s?.title || s?.urlBase || nodeId
}

async function runPlan() {
  loading.value = true
  errorText.value = ''
  plan.value = null
  enabled.value = {}
  try {
    const res = await bookmarksReclassifyPlan(props.folderId)
    plan.value = res
    const next: Record<string, boolean> = {}
    for (const g of res.groups) if (!g.keep) next[keyOf(g)] = true
    enabled.value = next
  } catch {
    // http 客户端已弹过 toast，这里只收尾
    errorText.value = '归类失败了，请稍后再试。'
  } finally {
    loading.value = false
  }
}

async function apply() {
  if (applying.value || selectedGroups.value.length === 0) return
  applying.value = true
  try {
    const groups = selectedGroups.value.map((g) => ({
      folderName: g.folderName,
      folderId: g.folderId ?? null,
      nodeIds: g.nodeIds,
    }))
    const root = await bookmarksReclassifyApply(props.folderId, groups)
    bookmarkStore.setLayout(root)
    useToastStore().success('已按新的分类整理完成')
    close()
  } catch {
    // 失败 toast 由 http 客户端统一处理
  } finally {
    applying.value = false
  }
}

function close() {
  emit('update:open', false)
}

// 原生 dialog 的 close（Esc / 点遮罩）统一同步给父组件
function onNativeClose() {
  if (props.open) emit('update:open', false)
}

watch(
  () => props.open,
  (open) => {
    const el = dialogRef.value
    if (!el) return
    if (open) {
      if (!el.open) el.showModal()
      runPlan()
    } else if (el.open) {
      el.close()
    }
  },
)
</script>
