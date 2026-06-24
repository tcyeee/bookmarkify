<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div>
      <h3 class="text-xl font-semibold">导入书签</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">从 Chrome 导出的 HTML 文件中批量导入书签。</p>
    </div>

    <!-- 上传区域 -->
    <label
      class="relative flex flex-col items-center justify-center gap-4 w-full rounded-2xl border-2 border-dashed cursor-pointer transition-colors"
      :class="[
        isDragging
          ? 'border-sky-400 bg-sky-50 dark:bg-sky-950/30'
          : 'border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50 hover:border-sky-300 hover:bg-sky-50/50 dark:hover:bg-sky-950/20',
        importing ? 'pointer-events-none opacity-60' : '',
      ]"
      style="min-height: 220px"
      @dragover.prevent="isDragging = true"
      @dragleave.prevent="isDragging = false"
      @drop.prevent="handleDrop">
      <input ref="fileInputRef" type="file" accept=".html,.htm" class="sr-only" :disabled="importing" @change="handleFileChange" />

      <div class="flex flex-col items-center gap-3 px-6 py-8 text-center select-none">
        <div class="flex items-center justify-center w-14 h-14 rounded-2xl bg-white dark:bg-slate-800 shadow-sm border border-slate-100 dark:border-slate-700">
          <Icon
            :icon="importing ? 'memory:rotate-clockwise' : 'memory:upload'"
            class="size-7 text-sky-500"
            :class="{ 'animate-spin': importing }" />
        </div>
        <div>
          <p class="text-base font-medium text-slate-700 dark:text-slate-200">
            {{ importing ? '正在导入，请稍候…' : '点击选择或拖拽文件到此处' }}
          </p>
          <p class="mt-1 text-sm text-slate-400 dark:text-slate-500">支持 Chrome / Edge 导出的 .html 书签文件</p>
        </div>
        <button
          v-if="!importing"
          type="button"
          class="cy-btn cy-btn-accent cy-btn-sm pointer-events-none">
          选择文件
        </button>
      </div>
    </label>

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

    <!-- 使用说明 -->
    <div class="rounded-xl border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/50 p-5 text-sm text-slate-500 dark:text-slate-400 space-y-2">
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
import { bookmarksUpload } from '@api'

const bookmarkStore = useBookmarkStore()

const fileInputRef = ref<HTMLInputElement>()
const importing = ref(false)
const isDragging = ref(false)
const statusMessage = ref('')
const statusType = ref<'default' | 'success' | 'error'>('default')

const statusClass = computed(() =>
  statusType.value === 'success'
    ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-200'
    : statusType.value === 'error'
      ? 'bg-rose-50 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200'
      : 'bg-sky-50 text-sky-700 dark:bg-sky-900/40 dark:text-sky-200'
)

function handleDrop(event: DragEvent) {
  isDragging.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) processFile(file)
}

async function handleFileChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (file) processFile(file)
}

async function processFile(file: File) {
  if (!file.name.endsWith('.html') && !file.name.endsWith('.htm')) {
    ElMessage.warning('请上传从 Chrome 导出的 HTML 书签文件')
    if (fileInputRef.value) fileInputRef.value.value = ''
    return
  }

  importing.value = true
  statusMessage.value = '正在导入书签，请稍候...'
  statusType.value = 'default'

  try {
    await bookmarksUpload(file)
    statusMessage.value = '导入完成！书签已同步到你的书签鸭。'
    statusType.value = 'success'
    bookmarkStore.update()
  } catch (error: any) {
    statusMessage.value = error?.msg || error?.message || '导入失败，请稍后重试。'
    statusType.value = 'error'
  } finally {
    importing.value = false
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
