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
        <div
          v-else
          class="mt-2 overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800 bg-white/60 dark:bg-slate-900/40"
        >
          <table class="min-w-full divide-y divide-slate-200 dark:divide-slate-800 text-sm">
            <thead class="bg-slate-50 dark:bg-slate-900/60 text-slate-600 dark:text-slate-300">
              <tr>
                <th scope="col" class="px-4 py-2 text-left font-medium">书签</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-200 dark:divide-slate-800 text-slate-800 dark:text-slate-100">
              <tr
                v-for="bookmark in bookmarks"
                :key="bookmark.bookmarkUserLinkId || bookmark.bookmarkId"
                class="hover:bg-slate-50 dark:hover:bg-slate-900/70 transition-colors"
              >
                <td class="px-4 py-2 align-middle">
                  <div class="flex items-center gap-2 min-w-0">
                    <div
                      class="relative h-7 w-7 shrink-0 rounded-md bg-white/70 dark:bg-slate-800 flex items-center justify-center ring-1 ring-slate-200 dark:ring-slate-700"
                    >
                      <span
                        class="absolute -top-0.5 -right-0.5 h-2 w-2 rounded-full ring-2 ring-white dark:ring-slate-900"
                        :class="bookmark.isActivity ? 'bg-emerald-500' : 'bg-rose-500'"
                        aria-hidden="true"
                      />
                      <div class="h-7 w-7 overflow-hidden rounded-md">
                        <img
                          class="h-full w-full object-contain"
                          :src="`data:image/png;base64,${bookmark.iconBase64}`"
                          :alt="bookmark.title"
                        />
                      </div>
                    </div>
                    <div class="min-w-0">
                      <a
                        class="text-sm font-medium text-slate-900 dark:text-slate-100 truncate hover:text-sky-600 dark:hover:text-sky-400"
                        :title="bookmark.description || bookmark.urlFull || bookmark.title"
                        :href="bookmark.urlFull"
                        target="_blank"
                        rel="noopener"
                      >
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

onMounted(fetchBookmarks)
</script>
