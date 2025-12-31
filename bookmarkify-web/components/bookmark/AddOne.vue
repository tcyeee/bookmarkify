<template>
  <div>
    <div class="h-20 w-20 rounded-2xl bg-white flex justify-center items-center" @click="sysStore.addBookmarkDialogVisible = true">
      <span class="icon--add icon-size-35 text-gray-400" />
    </div>

    <dialog id="dialog_add" class="cy-modal">
      <div class="cy-modal-box min-h-100">
        <!-- Button for create bookmark -->
        <div class="fixed bottom-8 left-8 right-8">
          <transition name="el-fade-in">
            <div v-if="searchResults.length > 0 && data.input" class="mb-4 bg-white/90 backdrop-blur-sm rounded-xl shadow-lg max-h-60 overflow-y-auto p-2 border border-gray-100">
              <div v-for="item in searchResults" :key="item.id" class="flex items-center gap-3 p-2 hover:bg-gray-100/80 cursor-pointer rounded-lg transition-colors" @click="selectBookmark(item)">
                <img v-if="item.iconBase64" :src="`data:image/png;base64,${item.iconBase64}`" class="w-8 h-8 rounded-full object-cover shadow-sm" />
                <div v-else class="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center shadow-sm">
                  <span class="icon--earth text-gray-400" />
                </div>
                <div class="flex flex-col overflow-hidden flex-1">
                  <span class="text-sm font-bold truncate text-gray-800">{{
                    item.title || item.appName || item.urlHost
                  }}</span>
                  <span class="text-xs text-gray-500 truncate">{{ item.description || item.urlHost }}</span>
                </div>
              </div>
            </div>
          </transition>

          <transition name="el-fade-in">
            <div v-if="data.notice" class="cy-chat cy-chat-start mb-4">
              <div class="cy-chat-bubble">{{ data.notice }}</div>
            </div>
          </transition>

          <label class="cy-input flex items-center gap-2 w-full" :class="data.urlIsTrue ? 'cy-input-success' : ''">
            <span class="icon--earth icon-size-30 text-gray-500" />
            <div>http://</div>
            <input v-model="data.input" type="text" placeholder="输入网址.." @input="checkInput" @keyup.enter="addOne" />
            <kbd class="cy-kbd cursor-pointer" @click="addOne">&emsp;enter&emsp; </kbd>
          </label>
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

const handleSearch = useDebounceFn(async (val: string) => {
  if (!val) {
    searchResults.value = []
    return
  }
  try {
    const res = await bookmarksSearch(val)
    searchResults.value = res || []
  } catch (e) {
    console.error(e)
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

function addOne() {
  if (!data.input) return
  if (!isUrl(data.input)) {
    data.notice = '你输入的网址看起来有点怪...'
  }

  bookmarksAddOne(data.input).then((res: HomeItem) => {
    emit('success', res)
    data.notice = '添加成功!'
    data.input = undefined
    setTimeout(() => {
      data.notice = undefined
      sysStore.addBookmarkDialogVisible = false
    }, 500)
  })
}

function checkInput() {
  if (!data.input) {
    searchResults.value = []
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
