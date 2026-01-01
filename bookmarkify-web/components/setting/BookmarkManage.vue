<template>
  <div class="space-y-2 text-slate-900 dark:text-slate-100 transition-colors">
    <h3 class="pb-5 text-xl font-semibold text-slate-900 dark:text-slate-100 transition-colors">导入 Chrome 书签</h3>

    <div class="bg-white dark:bg-slate-950/80 transition-colors">
      <div class="flex flex-wrap items-center gap-3">
        <label class="cy-btn cy-btn-soft cursor-pointer flex items-center gap-2">
          <input ref="fileInputRef" type="file" accept=".html,.htm" class="hidden" @change="handleFileChange" />
          <span class="icon--memory-upload icon-size-22"></span>
          <span>选择书签文件</span>
        </label>

        <button class="cy-btn cy-btn-accent" :disabled="!selectedFile || importing" @click="handleImport">
          <span v-if="importing">导入中...</span>
          <span v-else>开始导入</span>
        </button>

        <button v-if="selectedFile" class="cy-btn cy-btn-ghost" :disabled="importing" @click="handleReset">清除选择</button>
      </div>

      <div class="mt-3 text-sm text-slate-600 dark:text-slate-300 transition-colors">
        <span v-if="selectedFile">已选择：{{ selectedFile.name }}</span>
        <span v-else>尚未选择文件</span>
      </div>

      <ul
        class="mt-4 pl-5 list-disc space-y-1 text-sm text-slate-600 dark:text-slate-300 transition-colors marker:text-slate-500 dark:marker:text-slate-400">
        <li>打开 Chrome 设置 &gt; 书签管理器 &gt; 右上角「⋮」&gt; 导出书签。</li>
        <li>将导出的 `.html` 文件在此处选择并导入。</li>
        <li>导入过程中不会覆盖你已有的书签，仅做合并。</li>
      </ul>

      <div v-if="statusMessage" class="mt-4 rounded-md px-4 py-3 text-sm transition-colors" :class="statusClass">
        {{ statusMessage }}
      </div>
    </div>

    <h3 class="pt-8 pb-3 text-xl font-semibold text-slate-900 dark:text-slate-100 transition-colors">管理我的书签</h3>

    <div class="bg-white dark:bg-slate-950/80 transition-colors">
      <div class="flex flex-wrap items-center gap-3 justify-between">
        <div class="flex flex-wrap items-center gap-2">
          <input
            v-model="keyword"
            type="text"
            placeholder="输入标题、描述或域名搜索"
            class="cy-input cy-input-sm min-w-[220px]"
            :disabled="bookmarksLoading"
            @keyup.enter="handleSearchBookmarks"
          />
          <button class="cy-btn cy-btn-soft" :disabled="bookmarksLoading" @click="handleSearchBookmarks">搜索</button>
          <button class="cy-btn cy-btn-ghost" :disabled="bookmarksLoading" @click="handleResetSearch">重置</button>
        </div>
      </div>

      <div class="mt-4">
        <div v-if="bookmarksLoading" class="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
          <span class="icon--loading icon-size-18 animate-spin" />
          <span>正在加载书签...</span>
        </div>
        <div v-else-if="!bookmarks.length" class="rounded-md border border-dashed border-slate-200 dark:border-slate-800 px-4 py-6 text-sm text-slate-600 dark:text-slate-300">
          尚未发现你的书签，可以先通过上方导入或在主页添加。
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="bookmark in bookmarks"
            :key="bookmark.bookmarkUserLinkId || bookmark.bookmarkId"
            class="rounded-lg border border-slate-200 dark:border-slate-800 bg-slate-50/80 dark:bg-slate-900/60 p-4 transition-colors"
          >
            <div class="flex items-center gap-3">
              <div class="h-10 w-10 rounded-md bg-white/70 dark:bg-slate-800 flex items-center justify-center overflow-hidden">
                <img
                  class="h-full w-full object-contain"
                  :src="`data:image/png;base64,${bookmark.iconBase64}`"
                  :alt="bookmark.title"
                />
              </div>
              <div class="flex-1 min-w-0">
                <div class="text-base font-medium text-slate-900 dark:text-slate-100 truncate">{{ bookmarkTitle(bookmark) }}</div>
                <div class="text-xs text-slate-500 dark:text-slate-400 truncate">{{ bookmarkUrl(bookmark) }}</div>
              </div>
              <span
                class="rounded-full px-3 py-1 text-xs font-medium"
                :class="
                  bookmark.isActivity
                    ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-200'
                    : 'bg-amber-50 text-amber-700 dark:bg-amber-900/40 dark:text-amber-200'
                "
              >
                {{ bookmark.isActivity ? '正常' : '待检查' }}
              </span>
            </div>
            <p class="mt-2 text-sm text-slate-600 dark:text-slate-300 line-clamp-2 wrap-break-word">
              {{ bookmark.description || '暂无描述' }}
            </p>

          </div>
        </div>
      </div>

      <div v-if="bookmarkError" class="mt-4 rounded-md bg-rose-50 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200 px-4 py-3 text-sm">
        {{ bookmarkError }}
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksList } from '@api'
import type { Bookmark } from '@typing'

const fileInputRef = ref<HTMLInputElement>()
const selectedFile = ref<File | null>(null)
const importing = ref(false)
const statusMessage = ref('')
const statusType = ref<'default' | 'success' | 'error'>('default')
const bookmarksLoading = ref(false)
const bookmarkError = ref('')
const bookmarks = ref<Bookmark[]>([])
const keyword = ref('')

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]

  statusMessage.value = ''
  statusType.value = 'default'

  if (!file) {
    selectedFile.value = null
    return
  }

  // 只简单校验后缀/类型，详细校验交给后端
  if (!file.name.endsWith('.html') && !file.name.endsWith('.htm')) {
    ElMessage.warning('请上传从 Chrome 导出的 HTML 书签文件')
    selectedFile.value = null
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
    return
  }

  selectedFile.value = file
}

function handleReset() {
  selectedFile.value = null
  statusMessage.value = ''
  statusType.value = 'default'
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

const statusClass = computed(() =>
  statusType.value === 'success'
    ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-200'
    : statusType.value === 'error'
    ? 'bg-rose-50 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200'
    : 'bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100'
)

async function handleImport() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择要导入的书签文件')
    return
  }

  importing.value = true
  statusMessage.value = '正在导入书签，请稍候...'
  statusType.value = 'default'

  try {
    // TODO: 在此处接入后端导入 API，将 selectedFile 作为 FormData 上传
    // const formData = new FormData()
    // formData.append('file', selectedFile.value)
    // await importBookmarks(formData)

    // 这里先做个前端假等待，等你接后端
    await new Promise((resolve) => setTimeout(resolve, 1000))

    statusMessage.value = '导入完成！你可以在主页查看新书签。'
    statusType.value = 'success'
  } catch (error: any) {
    console.error(error)
    statusMessage.value = error?.message || '导入失败，请稍后重试。'
    statusType.value = 'error'
  } finally {
    importing.value = false
  }
}

function bookmarkTitle(bookmark: Bookmark) {
  return bookmark.title || bookmark.urlBase || '未命名书签'
}

function bookmarkUrl(bookmark: Bookmark) {
  return bookmark.urlFull || bookmark.urlBase || '#'
}

function bookmarkInitial(bookmark: Bookmark) {
  return bookmarkTitle(bookmark).slice(0, 1).toUpperCase()
}

async function fetchBookmarks() {
  bookmarksLoading.value = true
  bookmarkError.value = ''
  try {
    const payload = keyword.value.trim() ? { name: keyword.value.trim() } : {}
    const res = await bookmarksList(payload)
    bookmarks.value = res ?? []
  } catch (error: any) {
    bookmarkError.value = error?.msg || error?.message || '获取书签失败，请稍后重试。'
  } finally {
    bookmarksLoading.value = false
  }
}

function handleSearchBookmarks() {
  fetchBookmarks()
}

function handleResetSearch() {
  keyword.value = ''
  fetchBookmarks()
}

function copyUrl(url: string) {
  if (!url || url === '#') return
  navigator.clipboard.writeText(url).then(
    () => ElMessage.success('已复制到剪贴板'),
    () => ElMessage.error('复制失败，请手动复制')
  )
}

onMounted(fetchBookmarks)
</script>
