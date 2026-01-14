<template>
  <div class="space-y-3 text-slate-900 dark:text-slate-100 transition-colors">
    <div class="flex items-start justify-between gap-3">
      <div>
        <h3 class="text-xl font-semibold">书签管理</h3>
        <p class="mt-1 text-sm text-slate-600 dark:text-slate-300">导入 Chrome 书签并快速检索当前账号的链接。</p>
      </div>
      <label class="cy-btn cy-btn-accent flex items-center gap-2 cursor-pointer" :class="{ 'opacity-70 pointer-events-none': importing }">
        <input ref="fileInputRef" type="file" accept=".html,.htm" class="hidden" :disabled="importing" @change="handleFileChange" />
        <span class="icon--memory-upload icon-size-20"></span>
        <span>{{ importing ? '导入中…' : '导入书签' }}</span>
      </label>
    </div>

    <div class="bg-white dark:bg-slate-950/80 transition-colors rounded-lg border border-slate-200 dark:border-slate-800">
      <div class="flex flex-wrap items-center gap-2 border-b border-slate-100 dark:border-slate-800 px-4 py-3">
        <input v-model="keyword" type="text" placeholder="输入标题、描述或域名搜索" class="cy-input cy-input-sm min-w-[220px] flex-1" :disabled="bookmarksLoading" @keyup.enter="handleSearchBookmarks" />
        <button class="cy-btn cy-btn-soft" :disabled="bookmarksLoading" @click="handleSearchBookmarks">搜索</button>
        <button class="cy-btn cy-btn-ghost" :disabled="bookmarksLoading" @click="handleResetSearch">重置</button>
      </div>

      <div v-if="statusMessage" class="mx-4 mt-3 rounded-md px-4 py-3 text-sm transition-colors" :class="statusClass">
        {{ statusMessage }}
      </div>

      <div class="p-4">
        <div v-if="bookmarksLoading" class="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
          <span class="icon--memory-rotate-clockwise icon-size-18 animate-spin" />
          <span>正在加载书签...</span>
        </div>
        <div v-else-if="!bookmarks.length" class="rounded-md border border-dashed border-slate-200 dark:border-slate-800 px-4 py-6 text-sm text-slate-600 dark:text-slate-300">
          尚未发现你的书签，可以先导入或在主页添加。
        </div>
        <div v-else class="mt-1 overflow-x-auto">
          <table class="min-w-full divide-y divide-slate-200 dark:divide-slate-800 text-sm bg-white/60 dark:bg-slate-900/40 rounded-lg">
            <thead class="bg-slate-50 dark:bg-slate-900/60 text-slate-600 dark:text-slate-300">
              <tr>
                <th scope="col" class="px-4 py-2 text-left font-medium">书签</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-200 dark:divide-slate-800 text-slate-800 dark:text-slate-100">
              <tr v-for="bookmark in bookmarks" :key="bookmark.bookmarkUserLinkId || bookmark.bookmarkId" class="hover:bg-slate-50 dark:hover:bg-slate-900/70 transition-colors">
                <td class="px-4 py-2 align-middle">
                  <div class="flex items-center gap-2 min-w-0">
                    <div class="relative h-7 w-7 shrink-0 rounded-md bg-white/70 dark:bg-slate-800 flex items-center justify-center ring-1 ring-slate-200 dark:ring-slate-700">
                      <span class="absolute -top-0.5 -right-0.5 h-2 w-2 rounded-full ring-2 ring-white dark:ring-slate-900" :class="bookmark.isActivity ? 'bg-emerald-500' : 'bg-rose-500'" aria-hidden="true" />
                      <div class="h-7 w-7 overflow-hidden rounded-md">
                        <img class="h-full w-full object-contain" :src="bookmark.iconBase64" :alt="bookmark.title" />
                      </div>
                    </div>
                    <div class="min-w-0">
                      <a class="text-sm font-medium text-slate-900 dark:text-slate-100 truncate hover:text-sky-600 dark:hover:text-sky-400" :title="bookmark.description || bookmark.urlFull || bookmark.title" :href="bookmark.urlFull" target="_blank" rel="noopener">
                        {{ bookmark.title }}
                      </a>
                      <div class="text-xs text-slate-500 dark:text-slate-400 truncate" :title="bookmark.urlBase">
                        {{ bookmark.urlBase || '—' }}
                      </div>
                    </div>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="bookmarks.length" class="mt-3 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-600 dark:text-slate-400">
          <div>
            共 {{ total }} 条，当前第 {{ currentPage }} / {{ totalPages }} 页
          </div>
          <div class="flex items-center gap-2">
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-xs" :disabled="currentPage <= 1 || bookmarksLoading" @click="handlePageChange(currentPage - 1)">
              上一页
            </button>
            <span>{{ currentPage }} / {{ totalPages }}</span>
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-xs" :disabled="currentPage >= totalPages || bookmarksLoading" @click="handlePageChange(currentPage + 1)">
              下一页
            </button>
            <select v-model.number="pageSize" class="cy-input cy-input-sm w-24" :disabled="bookmarksLoading" @change="handlePageSizeChange">
              <option :value="10">每页 10 条</option>
              <option :value="20">每页 20 条</option>
              <option :value="50">每页 50 条</option>
            </select>
          </div>
        </div>
      </div>

      <div v-if="bookmarkError" class="mx-4 mb-4 rounded-md bg-rose-50 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200 px-4 py-3 text-sm">
        {{ bookmarkError }}
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksList, bookmarksUpload } from '@api'
import type { BookmarkShow } from '@typing'

const bookmarkStore = useBookmarkStore()

const fileInputRef = ref<HTMLInputElement>()
const selectedFile = ref<File | null>(null)
const importing = ref(false)
const statusMessage = ref('')
const statusType = ref<'default' | 'success' | 'error'>('default')
const bookmarksLoading = ref(false)
const bookmarkError = ref('')
const bookmarks = ref<BookmarkShow[]>([])
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const totalPages = computed(() => {
  if (!total.value || !pageSize.value) return 1
  const pages = Math.ceil(total.value / pageSize.value)
  return pages > 0 ? pages : 1
})

async function handleFileChange(event: Event) {
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

  await handleImport()
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
    await bookmarksUpload(selectedFile.value)
    statusMessage.value = '导入完成!'
    statusType.value = 'success'
    await fetchBookmarks()
    bookmarkStore.update()
  } catch (error: any) {
    console.error(error)
    statusMessage.value = error?.msg || error?.message || '导入失败，请稍后重试。'
    statusType.value = 'error'
  } finally {
    importing.value = false
    selectedFile.value = null
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
  }
}

async function fetchBookmarks() {
  bookmarksLoading.value = true
  bookmarkError.value = ''
  try {
    const payload = {
      ...(keyword.value.trim() ? { name: keyword.value.trim() } : {}),
      currentPage: currentPage.value,
      pageSize: pageSize.value,
    }
    const res = await bookmarksList(payload)
    bookmarks.value = res?.records ?? []
    total.value = res?.total ?? 0
    if (typeof res?.current === 'number') currentPage.value = res.current
    if (typeof res?.size === 'number') pageSize.value = res.size
  } catch (error: any) {
    bookmarkError.value = error?.msg || error?.message || '获取书签失败，请稍后重试。'
  } finally {
    bookmarksLoading.value = false
  }
}

function handleSearchBookmarks() {
  currentPage.value = 1
  fetchBookmarks()
}

function handleResetSearch() {
  keyword.value = ''
  currentPage.value = 1
  fetchBookmarks()
}

function handlePageChange(page: number) {
  if (page < 1 || page > totalPages.value) return
  if (page === currentPage.value) return
  currentPage.value = page
  fetchBookmarks()
}

function handlePageSizeChange() {
  currentPage.value = 1
  fetchBookmarks()
}

onMounted(fetchBookmarks)
</script>
