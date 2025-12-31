<template>
  <div>
    <div
      class="h-20 w-20 rounded-2xl bg-white flex justify-center items-center border border-gray-100 shadow-sm hover:shadow-lg hover:-translate-y-1 transition"
      @click="sysStore.addBookmarkDialogVisible = true">
      <span class="icon--add icon-size-35 text-gray-400" />
    </div>

    <dialog id="dialog_add" class="cy-modal">
      <div class="cy-modal-box max-w-2xl bg-linear-to-b from-white to-slate-50 border border-gray-100 shadow-xl">
        <div class="flex items-start justify-between gap-3">
          <div class="flex items-start gap-3">
            <div class="h-12 w-12 rounded-2xl bg-indigo-50 text-indigo-500 flex items-center justify-center shadow-inner">
              <span class="icon--add icon-size-20" />
            </div>
            <div>
              <div class="text-lg font-semibold text-gray-800">添加 / 关联书签</div>
              <p class="text-sm text-gray-500">粘贴网址，我们会自动帮你匹配或创建</p>
            </div>
          </div>
          <button
            type="button"
            class="h-8 w-8 rounded-full text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition"
            @click="sysStore.addBookmarkDialogVisible = false">
            ✕
          </button>
        </div>

        <div class="mt-6 space-y-3">
          <label class="cy-input flex items-center gap-3 w-full shadow-sm focus-within:ring-2 focus-within:ring-indigo-100" :class="data.urlIsTrue ? 'cy-input-success' : ''">
            <span class="icon--earth icon-size-24 text-gray-500" />
            <input
              ref="inputRef"
              v-model="data.input"
              type="text"
              class="flex-1 bg-transparent focus:outline-none"
              placeholder="https://example.com/article"
              @input="checkInput"
              @keyup.enter="addOne" />
            <button
              type="button"
              class="px-3 py-1.5 rounded-lg bg-indigo-500 text-white text-sm font-semibold hover:bg-indigo-600 active:bg-indigo-700 transition disabled:opacity-50 disabled:cursor-not-allowed"
              :disabled="!data.urlIsTrue"
              @click="addOne">
              添加
            </button>
          </label>

          <div class="flex items-center justify-between text-xs text-gray-500 px-1">
            <span>按 Enter 或 右侧按钮快速提交</span>
            <span v-if="data.urlIsTrue" class="text-emerald-500 font-semibold">链接格式正确</span>
            <span v-else-if="data.input" class="text-amber-500 font-semibold">请检查链接格式</span>
          </div>

          <transition name="el-fade-in">
            <div v-if="data.notice" class="cy-chat cy-chat-start">
              <div class="cy-chat-bubble shadow-sm">{{ data.notice }}</div>
            </div>
          </transition>

          <div v-if="data.input" class="rounded-xl border border-gray-100 bg-white/90 max-h-72 overflow-y-auto p-3 space-y-2">
            <div class="flex items-center justify-between text-xs text-gray-500">
              <span class="font-semibold">他人分享的书签</span>
              <div class="flex items-center gap-2">
                <div v-if="isSearching" class="h-3 w-3 border-2 border-indigo-200 border-t-indigo-500 rounded-full animate-spin" />
                <span v-if="searchResults.length" class="text-gray-500">{{ searchResults.length }} 条匹配</span>
              </div>
            </div>

            <transition name="el-fade-in" mode="out-in">
              <div v-if="searchResults.length">
                <div
                  v-for="item in searchResults"
                  :key="item.id"
                  class="flex items-center gap-3 p-2 hover:bg-gray-100/80 cursor-pointer rounded-lg transition-colors border border-transparent"
                  @click="selectBookmark(item)">
                  <img v-if="item.iconBase64" :src="`data:image/png;base64,${item.iconBase64}`" class="w-8 h-8 rounded-full object-cover shadow-sm" />
                  <div v-else class="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center shadow-sm">
                    <span class="icon--earth text-gray-400" />
                  </div>
                  <div class="flex flex-col overflow-hidden flex-1">
                    <span class="text-sm font-bold truncate text-gray-800">{{ item.title || item.appName || item.urlHost }}</span>
                    <span class="text-xs text-gray-500 truncate">{{ item.description || item.urlHost }}</span>
                  </div>
                  <span class="text-xs text-indigo-500 font-semibold">添加</span>
                </div>
              </div>
              <div v-else-if="showEmptyState" class="flex items-center gap-3 text-sm text-gray-600 py-3">
                <div class="h-10 w-10 rounded-full bg-gray-100 flex items-center justify-center">
                  <span class="icon--add text-gray-400" />
                </div>
                <div>
                  <div class="font-semibold text-gray-700">暂无可关联书签</div>
                  <p class="text-xs text-gray-500">直接回车即可创建新的书签</p>
                </div>
              </div>
              <div v-else class="text-xs text-gray-400 py-3">输入内容即可开始匹配</div>
            </transition>
          </div>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button @click="sysStore.addBookmarkDialogVisible = false">close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksAddOne, bookmarksSearch, bookmarksLinkOne } from '@api'
import type { HomeItem } from '@typing'
import { useDebounceFn } from '@vueuse/core'

const sysStore = useSysStore()

const searchResults = ref<any[]>([])
const isSearching = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

const handleSearch = useDebounceFn(async (val: string) => {
  if (!val) {
    searchResults.value = []
    isSearching.value = false
    return
  }
  isSearching.value = true
  try {
    const res = await bookmarksSearch(val)
    searchResults.value = res || []
  } catch (e) {
    console.error(e)
  } finally {
    isSearching.value = false
  }
}, 500)

// 监测到添加书签窗口的显示与否
watchEffect(() => {
  toggleAddDialog(sysStore.addBookmarkDialogVisible)
})

// 根据标志位显示或关闭添加书签对话框
function toggleAddDialog(flag: boolean) {
  if (!import.meta.client) return
  const element = document.getElementById('dialog_add') as HTMLDialogElement
  if (!element) return
  if (flag) {
    element.showModal()
    nextTick(() => inputRef.value?.focus())
  } else {
    element.close()
  }
}

const emit = defineEmits(['success'])
const data = reactive<{
  status: boolean
  input?: string
  urlIsTrue: boolean
  notice?: string
}>({
  urlIsTrue: false,
  status: false,
})

const showEmptyState = computed(
  () => data.input && !isSearching.value && searchResults.value.length === 0 && data.urlIsTrue
)

function addOne() {
  if (!data.input) return
  if (!isUrl(data.input)) {
    data.notice = '你输入的网址看起来有点怪...'
    return
  }

  bookmarksAddOne(data.input).then((res: HomeItem) => {
    emit('success', res)
    data.notice = '添加成功!'
    data.input = undefined
    data.urlIsTrue = false
    searchResults.value = []
    setTimeout(() => {
      data.notice = undefined
      sysStore.addBookmarkDialogVisible = false
    }, 500)
  })
}

function checkInput() {
  if (!data.input) {
    searchResults.value = []
    isSearching.value = false
    return
  }
  data.urlIsTrue = isUrl(data.input)
  if (data.urlIsTrue) data.notice = undefined
  handleSearch(data.input)
}

function selectBookmark(item: any) {
  bookmarksLinkOne(item.id).then((res: HomeItem) => {
    emit('success', res)
    data.notice = '关联成功!'
    data.input = undefined
    searchResults.value = []
    data.urlIsTrue = false
    setTimeout(() => {
      data.notice = undefined
      sysStore.addBookmarkDialogVisible = false
    }, 500)
  })
}

function isUrl(url: string): boolean {
  var pattern = RegExp(
    '^(https?:\\/\\/)?' + // 协议部分，可选，http或https开头
      '((([a-z\\d]([a-z\\d-]*[a-z\\d])*)\\.)+[a-z]{2,}|' + // 域名部分
      '((\\d{1,3}\\.){3}\\d{1,3}))' + // 或者IP地址部分
      '(\\:\\d+)?(\\/[-a-z\\d%_.~+]*)*' + // 端口号和路径部分
      '(\\?[;&a-z\\d%_.~+=-]*)?' + // 查询字符串
      '(\\#[-a-z\\d_]*)?$',
    'i'
  )
  return pattern.test(url)
}
</script>
