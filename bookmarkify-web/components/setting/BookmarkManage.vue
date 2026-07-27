<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div>
      <h3 class="text-xl font-semibold">导入书签</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">从 Chrome 导出的 HTML 文件中批量导入书签。</p>
    </div>

    <!-- 阶段：idle / importing — 上传区 -->
    <template v-if="phase !== 'reviewing'">
      <label
        class="relative flex flex-col items-center justify-center gap-4 w-full rounded-2xl border-2 border-dashed cursor-pointer transition-colors"
        :class="[
          isDragging
            ? 'border-sky-400 bg-sky-50 dark:bg-sky-950/30'
            : 'border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50 hover:border-sky-300 hover:bg-sky-50/50 dark:hover:bg-sky-950/20',
          phase !== 'idle' ? 'pointer-events-none opacity-60' : '',
        ]"
        style="min-height: 220px"
        @dragover.prevent="isDragging = true"
        @dragleave.prevent="isDragging = false"
        @drop.prevent="handleDrop">
        <input ref="fileInputRef" type="file" accept=".html,.htm" class="sr-only" :disabled="phase !== 'idle'" @change="handleFileChange" />
        <div class="flex flex-col items-center gap-3 px-6 py-8 text-center select-none">
          <div class="flex items-center justify-center w-14 h-14 rounded-2xl bg-white dark:bg-slate-800 shadow-sm border border-slate-100 dark:border-slate-700">
            <Icon
              :icon="phase !== 'idle' ? 'memory:rotate-clockwise' : 'memory:upload'"
              class="size-7 text-sky-500"
              :class="{ 'animate-spin': phase !== 'idle' }" />
          </div>
          <div>
            <p class="text-base font-medium text-slate-700 dark:text-slate-200">
              {{ phase === 'importing' ? '导入已开始，书签解析中…' : phase === 'loading' ? '正在读取文件…' : '点击选择或拖拽文件到此处' }}
            </p>
            <p class="mt-1 text-sm text-slate-400 dark:text-slate-500">支持 Chrome / Edge 导出的 .html 书签文件</p>
          </div>
          <button v-if="phase === 'idle'" type="button" class="cy-btn cy-btn-accent cy-btn-sm pointer-events-none">选择文件</button>
        </div>
      </label>
    </template>

    <!-- 阶段：reviewing — review 面板 -->
    <template v-if="phase === 'reviewing' && previewData">
      <div class="rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <!-- 头部统计 -->
        <div class="flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/60 border-b border-slate-200 dark:border-slate-700">
          <p class="text-sm text-slate-600 dark:text-slate-300">
            共 <span class="font-semibold">{{ previewData.total }}</span> 个书签
            <template v-if="previewData.duplicateCount > 0">
              ，其中 <span class="font-semibold text-amber-600 dark:text-amber-400">{{ previewData.duplicateCount }}</span> 个已有
            </template>
          </p>
          <p class="text-sm text-sky-600 dark:text-sky-400 font-medium">将导入 {{ selectedCount }} 个</p>
        </div>

        <!-- 书签列表（按文件夹分组） -->
        <div class="max-h-80 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
          <div v-for="group in groupedItems" :key="group.folder ?? '__root__'">
            <!-- 文件夹标题行 -->
            <div
              v-if="group.folder"
              class="flex items-center gap-2 px-4 py-2 bg-slate-50/70 dark:bg-slate-800/40 sticky top-0 z-10">
              <Icon icon="memory:folder" class="size-4 text-amber-500 shrink-0" />
              <span class="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide truncate">{{ group.folder }}</span>
            </div>
            <!-- 书签项 -->
            <label
              v-for="item in group.items"
              :key="item.url"
              class="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-800/50 cursor-pointer transition-colors">
              <input type="checkbox" class="cy-checkbox cy-checkbox-sm" :checked="checkedUrls.has(item.url)" @change="toggleUrl(item.url)" />
              <div class="flex-1 min-w-0">
                <p class="text-sm font-medium truncate" :class="item.isDuplicate ? 'text-slate-400 dark:text-slate-500' : 'text-slate-700 dark:text-slate-200'">
                  {{ item.title }}
                </p>
                <p class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ item.url }}</p>
              </div>
              <span v-if="item.isDuplicate" class="shrink-0 text-xs px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">已有</span>
            </label>
          </div>
        </div>

        <!-- 底部操作 -->
        <div class="flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/60 border-t border-slate-200 dark:border-slate-700">
          <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" @click="reset">取消</button>
          <button
            type="button"
            class="cy-btn cy-btn-accent cy-btn-sm"
            :disabled="selectedCount === 0"
            @click="startImport">
            开始导入（{{ selectedCount }} 个）
          </button>
        </div>
      </div>
    </template>

    <!-- 状态消息 -->
    <Transition name="fade-fast">
      <div
        v-if="statusMessage"
        class="flex items-start gap-3 rounded-xl px-4 py-3 text-sm transition-colors"
        :class="statusClass">
        <Icon :icon="statusType === 'success' ? 'memory:check-circle' : statusType === 'error' ? 'memory:close-circle' : 'memory:information'" class="size-5 mt-0.5 shrink-0" />
        <span>{{ statusMessage }}</span>
      </div>
    </Transition>

    <!-- 使用说明（仅 idle 时展示） -->
    <div v-if="phase === 'idle'" class="rounded-xl border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/50 p-5 text-sm text-slate-500 dark:text-slate-400 space-y-2">
      <p class="font-medium text-slate-600 dark:text-slate-300">如何导出 Chrome 书签？</p>
      <ol class="list-decimal list-inside space-y-1 text-slate-500 dark:text-slate-400">
        <li>打开 Chrome，点击右上角菜单 → 书签 → 书签管理器</li>
        <li>点击右上角三点图标 → 导出书签</li>
        <li>保存为 <code class="px-1 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-200 font-mono text-xs">.html</code> 文件后上传即可</li>
      </ol>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksUploadPreview, bookmarksUpload } from '@api'
import { HomeItemType } from '@typing'
import type * as t from '@typing'

type Phase = 'idle' | 'loading' | 'reviewing' | 'importing'

const bookmarkStore = useBookmarkStore()

const fileInputRef = ref<HTMLInputElement>()
const phase = ref<Phase>('idle')
const isDragging = ref(false)
const statusMessage = ref('')
const statusType = ref<'default' | 'success' | 'error'>('default')
const previewData = ref<t.BookmarkImportPreviewVO | null>(null)
const pendingFile = ref<File | null>(null)
const checkedUrls = ref(new Set<string>())

const statusClass = computed(() =>
  statusType.value === 'success'
    ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-200'
    : statusType.value === 'error'
      ? 'bg-rose-50 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200'
      : 'bg-sky-50 text-sky-700 dark:bg-sky-900/40 dark:text-sky-200'
)

const groupedItems = computed<Array<{ folder: string | null; items: t.BookmarkImportItemVO[] }>>(() => {
  if (!previewData.value) return []
  const map = new Map<string, t.BookmarkImportItemVO[]>()
  for (const item of previewData.value.items) {
    const key = item.folder ?? ''
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(item)
  }
  return [...map.entries()].map(([key, items]) => ({ folder: key || null, items }))
})

const selectedCount = computed(() => checkedUrls.value.size)

function toggleUrl(url: string) {
  if (checkedUrls.value.has(url)) {
    checkedUrls.value.delete(url)
  } else {
    checkedUrls.value.add(url)
  }
  // 触发响应式更新
  checkedUrls.value = new Set(checkedUrls.value)
}

function reset() {
  phase.value = 'idle'
  previewData.value = null
  pendingFile.value = null
  checkedUrls.value = new Set()
  statusMessage.value = ''
  if (fileInputRef.value) fileInputRef.value.value = ''
}

function handleDrop(event: DragEvent) {
  isDragging.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) processFile(file)
}

function handleFileChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (file) processFile(file)
}

async function processFile(file: File) {
  if (!file.name.endsWith('.html') && !file.name.endsWith('.htm')) {
    ElMessage.warning('请上传从 Chrome 导出的 HTML 书签文件')
    if (fileInputRef.value) fileInputRef.value.value = ''
    return
  }

  phase.value = 'loading'
  statusMessage.value = ''

  try {
    const data = await bookmarksUploadPreview(file)

    if (data.total === 0) {
      statusMessage.value = '文件中没有找到可导入的书签。'
      statusType.value = 'default'
      phase.value = 'idle'
      return
    }

    previewData.value = data
    pendingFile.value = file

    // 非重复项默认勾选
    const initial = new Set(data.items.filter((i) => !i.isDuplicate).map((i) => i.url))

    if (data.duplicateCount === 0) {
      // 无重复，直接导入
      checkedUrls.value = initial
      await startImport()
    } else {
      checkedUrls.value = initial
      phase.value = 'reviewing'
    }
  } catch (error: any) {
    statusMessage.value = error?.msg || error?.message || '读取文件失败，请重试。'
    statusType.value = 'error'
    phase.value = 'idle'
    if (fileInputRef.value) fileInputRef.value.value = ''
  }
}

async function startImport() {
  if (!pendingFile.value) return
  const skipUrls = previewData.value
    ? previewData.value.items.filter((i) => !checkedUrls.value.has(i.url)).map((i) => i.url)
    : []

  phase.value = 'importing'

  try {
    const nodes = await bookmarksUpload(pendingFile.value, skipUrls)
    bookmarkStore.addImportLoadingBatch(nodes)
    const loadingIds = nodes.filter((n) => n.type === HomeItemType.BOOKMARK_LOADING).map((n) => n.id)
    useImportProgressStore().startBatch(loadingIds)
    statusMessage.value = `导入已开始！共 ${nodes.length} 项正在后台解析，稍后会自动更新。`
    statusType.value = 'success'
    phase.value = 'idle'
  } catch (error: any) {
    statusMessage.value = error?.msg || error?.message || '导入失败，请稍后重试。'
    statusType.value = 'error'
    phase.value = 'idle'
  } finally {
    previewData.value = null
    pendingFile.value = null
    checkedUrls.value = new Set()
    if (fileInputRef.value) fileInputRef.value.value = ''
  }
}
</script>

<style scoped>
.fade-fast-enter-active,
.fade-fast-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}
.fade-fast-enter-from,
.fade-fast-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>
