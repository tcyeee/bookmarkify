<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div>
      <h3 class="text-xl font-semibold">{{ $t('bookmarkManage.title') }}</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ $t('bookmarkManage.desc') }}</p>
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
              :icon="phase !== 'idle' ? 'mdi:loading' : 'mdi:upload'"
              class="size-7 text-sky-500"
              :class="{ 'animate-spin': phase !== 'idle' }" />
          </div>
          <div>
            <p class="text-base font-medium text-slate-700 dark:text-slate-200">
              {{ phase === 'importing' ? $t('bookmarkManage.importing') : phase === 'loading' ? $t('bookmarkManage.loadingFile') : $t('bookmarkManage.dropHint') }}
            </p>
            <p class="mt-1 text-sm text-slate-400 dark:text-slate-500">{{ $t('bookmarkManage.supportedFormats') }}</p>
          </div>
          <button v-if="phase === 'idle'" type="button" class="cy-btn cy-btn-accent cy-btn-sm pointer-events-none">{{ $t('bookmarkManage.chooseFile') }}</button>
        </div>
      </label>
    </template>

    <!-- 阶段：reviewing — review 面板 -->
    <template v-if="phase === 'reviewing' && previewData">
      <div class="rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <!-- 头部统计 -->
        <div class="flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/60 border-b border-slate-200 dark:border-slate-700">
          <p class="text-sm text-slate-600 dark:text-slate-300">
            <i18n-t keypath="bookmarkManage.totalCount" tag="span">
              <template #total><span class="font-semibold">{{ previewData.total }}</span></template>
            </i18n-t>
            <i18n-t v-if="previewData.duplicateCount > 0" keypath="bookmarkManage.duplicateCount" tag="span">
              <template #count><span class="font-semibold text-amber-600 dark:text-amber-400">{{ previewData.duplicateCount }}</span></template>
            </i18n-t>
          </p>
          <p class="text-sm text-sky-600 dark:text-sky-400 font-medium">{{ $t('bookmarkManage.willImport', { count: selectedCount }) }}</p>
        </div>

        <!-- 书签列表（按文件夹分组） -->
        <div class="max-h-80 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
          <div v-for="group in groupedItems" :key="group.folder ?? '__root__'">
            <!-- 文件夹标题行 -->
            <div
              v-if="group.folder"
              class="flex items-center gap-2 px-4 py-2 bg-slate-50/70 dark:bg-slate-800/40 sticky top-0 z-10">
              <Icon icon="mdi:folder" class="size-4 text-amber-500 shrink-0" />
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
              <span v-if="item.isDuplicate" class="shrink-0 text-xs px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">{{ $t('bookmarkManage.existing') }}</span>
            </label>
          </div>
        </div>

        <!-- 底部操作 -->
        <div class="flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/60 border-t border-slate-200 dark:border-slate-700">
          <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" @click="reset">{{ $t('bookmarkManage.cancel') }}</button>
          <button
            type="button"
            class="cy-btn cy-btn-accent cy-btn-sm"
            :disabled="selectedCount === 0"
            @click="startImport">
            {{ $t('bookmarkManage.startImport', { count: selectedCount }) }}
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
        <Icon :icon="statusType === 'success' ? 'mdi:check-circle' : statusType === 'error' ? 'mdi:close-circle' : 'mdi:information'" class="size-5 mt-0.5 shrink-0" />
        <span>{{ statusMessage }}</span>
      </div>
    </Transition>

    <!-- 使用说明（仅 idle 时展示） -->
    <div v-if="phase === 'idle'" class="rounded-xl border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/50 p-5 text-sm text-slate-500 dark:text-slate-400 space-y-2">
      <p class="font-medium text-slate-600 dark:text-slate-300">{{ $t('bookmarkManage.howToExport') }}</p>
      <ol class="list-decimal list-inside space-y-1 text-slate-500 dark:text-slate-400">
        <li>{{ $t('bookmarkManage.step1') }}</li>
        <li>{{ $t('bookmarkManage.step2') }}</li>
        <li>
          {{ $t('bookmarkManage.step3Before') }}<code class="px-1 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-200 font-mono text-xs">{{ $t('bookmarkManage.step3Code') }}</code>{{ $t('bookmarkManage.step3After') }}
        </li>
      </ol>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksUploadPreview, bookmarksUpload } from '@api'
import { HomeItemType } from '@typing'
import type * as t from '@typing'

type Phase = 'idle' | 'loading' | 'reviewing' | 'importing'

const { t: translate } = useI18n()
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
    useToastStore().warning(translate('bookmarkManage.invalidFileType'))
    if (fileInputRef.value) fileInputRef.value.value = ''
    return
  }

  phase.value = 'loading'
  statusMessage.value = ''

  try {
    const data = await bookmarksUploadPreview(file)

    if (data.total === 0) {
      statusMessage.value = translate('bookmarkManage.noBookmarksFound')
      statusType.value = 'default'
      phase.value = 'idle'
      return
    }

    previewData.value = data
    pendingFile.value = file

    if (data.duplicateCount === 0) {
      // 无重复，直接导入
      checkedUrls.value = new Set(data.items.map((i) => i.url))
      await startImport()
      return
    }

    // 有重复项，先确认是否去除重复书签，再进入 review 面板
    let skipDuplicates = true
    try {
      await useConfirmStore().confirm(translate('bookmarkManage.duplicateConfirmMessage', { count: data.duplicateCount }), {
        title: translate('bookmarkManage.duplicateConfirmTitle'),
        confirmText: translate('bookmarkManage.duplicateConfirmSkip'),
        cancelText: translate('bookmarkManage.duplicateConfirmKeep'),
        type: 'warning',
      })
    } catch {
      skipDuplicates = false
    }

    checkedUrls.value = new Set(data.items.filter((i) => !skipDuplicates || !i.isDuplicate).map((i) => i.url))
    phase.value = 'reviewing'
  } catch (error: any) {
    statusMessage.value = error?.msg || error?.message || translate('bookmarkManage.readFileFailed')
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
    // 批量导入同样只靠 WebSocket 推送解除 loading，逐项注册超时兜底（放宽到 60s：导入批量大时
    // 解析队列本身排队更久，避免和正常排队耗时打架产生误报式的重新拉取）
    loadingIds.forEach((id) => bookmarkStore.watchForResolution(id, 60000))
    statusMessage.value = translate('bookmarkManage.importStarted', { count: nodes.length })
    statusType.value = 'success'
    phase.value = 'idle'
  } catch (error: any) {
    statusMessage.value = error?.msg || error?.message || translate('bookmarkManage.importFailed')
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
