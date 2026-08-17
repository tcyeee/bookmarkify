<template>
  <dialog id="dialog_add" ref="dialogRef" class="cy-modal">
    <div class="cy-modal-box max-w-2xl bg-linear-to-b from-white to-slate-50 border border-gray-100 shadow-xl">
      <div class="flex items-start justify-between gap-3">
        <div class="flex items-start gap-3">
          <div class="h-12 w-12 rounded-2xl bg-indigo-50 text-indigo-500 flex items-center justify-center shadow-inner">
            <span class="icon--add icon-size-20" />
          </div>
          <div>
            <div class="text-lg font-semibold text-gray-800">添加 / 关联书签</div>
            <p class="text-sm text-gray-500">粘贴网址，我们会自动帮你匹配或创建</p>
            <!-- 从文件夹菜单进来时必须说明落点：同一个弹窗、两种归属，不写出来用户无从分辨 -->
            <p v-if="targetDir" class="mt-1 text-xs text-indigo-500 font-semibold">添加到「{{ targetDir.name }}」</p>
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
        <label
          class="cy-input flex items-center gap-3 w-full shadow-sm focus-within:ring-2 focus-within:ring-indigo-100"
          :class="data.urlIsTrue ? 'cy-input-success' : ''">
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
            :disabled="!data.urlIsTrue || submitting"
            @click="addOne">
            {{ submitting ? '添加中...' : '添加' }}
          </button>
        </label>

        <div class="flex items-center justify-between text-xs text-gray-500 px-1">
          <span>按 Enter 或 右侧按钮快速提交</span>
          <span v-if="data.urlIsTrue" class="text-emerald-500 font-semibold">链接格式正确</span>
          <span v-else-if="data.input" class="text-amber-500 font-semibold">请检查链接格式</span>
        </div>

        <!-- 本地/IP 地址：能加，但加进来不会有标题和图标，先说清楚 -->
        <div
          v-if="localOrIpNotice"
          class="flex items-start gap-2 rounded-xl border border-amber-100 bg-amber-50 px-3 py-2 text-xs text-amber-700">
          <span class="icon--earth icon-size-16 shrink-0 mt-0.5" />
          <span>{{ localOrIpNotice }}</span>
        </div>

        <transition name="fade">
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

          <transition name="fade" mode="out-in">
            <div v-if="searchResults.length">
              <div
                v-for="item in searchResults"
                :key="item.id"
                class="flex items-center gap-3 p-2 hover:bg-gray-100/80 cursor-pointer rounded-lg transition-colors border border-transparent"
                @click="selectBookmark(item)">
                <img v-if="item.logo?.url" :src="item.logo.url" class="w-8 h-8 object-cover" />
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
</template>

<script lang="ts" setup>
import { bookmarksAddOne, bookmarksLinkOne, bookmarksMoveNode, bookmarksSearch } from '@api'
import { HomeItemType, ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import { useBookmarkStore } from '@stores/bookmark.store'
import { canonicalUrlKey, isBookmarkableUrl, isScrapableUrl } from '@utils'
import { useDebounceFn } from '@vueuse/core'

const sysStore = useSysStore()
const bookmarkStore = useBookmarkStore()
const { $track } = useNuxtApp()

const emit = defineEmits<{ (e: 'success', res: UserLayoutNodeVO): void }>()

const dialogRef = ref<HTMLDialogElement | null>(null)
const searchResults = ref<any[]>([])
const isSearching = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

// 当前用户已持有的 canonical pageId 集合。比对对象是 BookmarkSearchVO.id，那也是 page.id；
// 用 bookmarkId(本用户的关联ID) 去比会永远不相等，判重形同虚设。
const ownedPageIds = computed(() => {
  const ids = new Set<string>()
  for (const node of Object.values(bookmarkStore.nodes)) {
    if (node.typeApp?.pageId) ids.add(node.typeApp.pageId)
  }
  return ids
})

// 当前用户已持有的网址（归一化后），用于在提交前就地判重，见 canonicalUrlKey 的取舍说明。
// LOADING 占位没有 typeApp、也就没有网址，落不进这个集合——那种情况仍由后端拦下。
const ownedUrlKeys = computed(() => {
  const keys = new Set<string>()
  for (const node of Object.values(bookmarkStore.nodes)) {
    const key = canonicalUrlKey(node.typeApp?.urlFull)
    if (key) keys.add(key)
  }
  return keys
})

const handleSearch = useDebounceFn(async (val: string) => {
  if (!val) {
    searchResults.value = []
    isSearching.value = false
    return
  }
  isSearching.value = true
  try {
    const res = await bookmarksSearch(val)
    // 剔除自己已经收藏过的：后端现在会拒绝重复关联，不过滤的话这些条目点下去只能得到一个错误提示。
    // pages/index.vue 的搜索结果一直是这么过滤的，这里之前漏了。
    searchResults.value = (res || []).filter((item: any) => !ownedPageIds.value.has(item.id))
  } catch (e) {
    console.error(e)
  } finally {
    isSearching.value = false
  }
}, 500)

// 本次添加的目标文件夹（由文件夹卡片菜单的「添加书签」写进 sysStore）。
// 节点可能在弹窗打开期间被删掉/解散，所以每次都按当前 store 校验类型，而不是信任那个 id
const targetDir = computed(() => {
  const id = sysStore.addBookmarkTargetDirId
  if (!id) return null
  const node = bookmarkStore.nodes[id]
  return node?.type === HomeItemType.BOOKMARK_DIR ? node : null
})

watchEffect(() => {
  toggleAddDialog(sysStore.addBookmarkDialogVisible)
})

// 目标文件夹是一次性的：关闭即失效。清理放在这里而不是各个打开入口，是因为入口有三个
// （工具栏、命令面板、文件夹菜单），漏掉任何一个都会让下一次添加悄悄落进上回那个文件夹
watch(
  () => sysStore.addBookmarkDialogVisible,
  (visible) => {
    if (!visible) sysStore.addBookmarkTargetDirId = null
  },
)

onMounted(() => {
  dialogRef.value?.addEventListener('close', handleNativeClose)
  dialogRef.value?.addEventListener('cancel', handleNativeClose)
})

onBeforeUnmount(() => {
  dialogRef.value?.removeEventListener('close', handleNativeClose)
  dialogRef.value?.removeEventListener('cancel', handleNativeClose)
})

function handleNativeClose() {
  if (sysStore.addBookmarkDialogVisible) sysStore.addBookmarkDialogVisible = false
}

function toggleAddDialog(flag: boolean) {
  if (!import.meta.client) return
  const element = dialogRef.value || (document.getElementById('dialog_add') as HTMLDialogElement | null)
  if (!element) return
  if (flag) {
    element.showModal()
    nextTick(() => inputRef.value?.focus())
  } else {
    element.close()
  }
}

const data = reactive<{
  status: boolean
  input?: string
  urlIsTrue: boolean
  notice?: string
}>({
  urlIsTrue: false,
  status: false,
})

const showEmptyState = computed(() => data.input && !isSearching.value && searchResults.value.length === 0 && data.urlIsTrue)

const submitting = ref(false)

function addOne() {
  if (!data.input || submitting.value) return
  if (!isBookmarkableUrl(data.input)) {
    data.notice = '你输入的网址看起来有点怪...'
    return
  }
  // 本地已有就不必往返一次再等后端弹 E126——同一份判断搜索结果那边一直在做（ownedPageIds），
  // 只有直接粘贴网址这条路以前漏了。漏判无所谓，后端仍是判重的唯一权威。
  const inputKey = canonicalUrlKey(data.input)
  if (inputKey && ownedUrlKeys.value.has(inputKey)) {
    data.notice = '该网址已经在你的书签里了'
    useToastStore().warning('该网址已经在你的书签里了')
    return
  }

  submitting.value = true
  // 落点在提交这一刻就定下来：请求回来时弹窗早已开始关闭，targetDir 那时可能已被清空
  const dirId = targetDir.value?.id ?? null
  bookmarksAddOne(data.input)
    .then((res: UserLayoutNodeVO) => {
      $track('bookmark-add')
      handleSuccess(res, dirId)
      // typeApp 已就绪但标记为不可访问：说明命中了后端「近期已检测过」的跳过重抓窗口(10 分钟)，
      // 本次添加根本没有真正发起抓取——不给出说明的话，用户会误以为书签信息「没经过正常解析流程」
      const skippedRecrawl = res?.typeApp && res.typeApp.isActivity === false
      if (skippedRecrawl) {
        useToastStore().warning('该网址近期已检测为无法访问，本次跳过了重新抓取，请稍后重试')
      }
      data.notice = skippedRecrawl ? '已添加(该网址近期检测无法访问)' : '添加成功!'
      data.input = undefined
      data.urlIsTrue = false
      searchResults.value = []
      setTimeout(() => {
        data.notice = undefined
        sysStore.addBookmarkDialogVisible = false
      }, 500)
    })
    // 必须接住：http.ts 的所有失败路径都是 Promise.reject，没有 catch 时每次添加失败都会在
    // 控制台留下一条 unhandled rejection，且对话框状态原地冻结——输入框内容和提示都停在提交前的
    // 样子，用户看不出这次到底走没走。错误 toast 由 http.ts 统一弹，这里只负责恢复可交互状态。
    .catch((error) => {
      $track('bookmark-add-fail')
      console.error('[AddOneDialog] 添加书签失败', error)
      data.notice = undefined
    })
    .finally(() => {
      submitting.value = false
    })
}

function checkInput() {
  if (!data.input) {
    searchResults.value = []
    isSearching.value = false
    return
  }
  data.urlIsTrue = isBookmarkableUrl(data.input)
  if (data.urlIsTrue) data.notice = undefined
  handleSearch(data.input)
}

/**
 * 本地 / IP 地址：**能收藏，但不会被抓取**。
 *
 * 这类网址（`localhost:5173`、`192.168.1.5:8080`，以及公网裸 IP）后端一律不抓，所以它们
 * 永远没有标题和图标，桌面上只会是一个通用圆圈。不提前说明的话，用户加完只会觉得
 * "怎么加了个空的"，然后反复删掉重加 —— 而重加多少次结果都一样。
 *
 * 提示而不是拦截：收藏自己本地的开发服务、家里 NAS 的管理页，本来就是正当用法。
 */
const localOrIpNotice = computed(() =>
  data.input && data.urlIsTrue && !isScrapableUrl(data.input)
    ? '这是本地 / IP 地址，我们不会抓取它的标题和图标，添加后显示为通用图标'
    : '',
)

function selectBookmark(item: any) {
  if (submitting.value) return
  submitting.value = true
  // 同 addOne：关联走的是同一个落点规则，快照同样要在提交时取
  const dirId = targetDir.value?.id ?? null
  bookmarksLinkOne(item.id)
    .then((res: UserLayoutNodeVO) => {
      $track('bookmark-link')
      handleSuccess(res, dirId)
      data.notice = '关联成功!'
      data.input = undefined
      searchResults.value = []
      data.urlIsTrue = false
      setTimeout(() => {
        data.notice = undefined
        sysStore.addBookmarkDialogVisible = false
      }, 500)
    })
    // 同 addOne：接住失败，避免 unhandled rejection 并恢复可交互状态
    .catch((error) => {
      $track('bookmark-link-fail')
      console.error('[AddOneDialog] 关联书签失败', error)
      data.notice = undefined
    })
    .finally(() => {
      submitting.value = false
    })
}

// 统一处理成功回调：通知外部并更新本地 store，用于立即显示占位或新书签。
// dirId 非空表示这次添加是从某个文件夹的菜单发起的，新书签要落进那个文件夹
function handleSuccess(res: UserLayoutNodeVO, dirId: string | null) {
  emit('success', res)
  const parentKey = dirId ?? ROOT_KEY
  if (res?.typeApp) {
    // 后端同步返回了完整数据(无需重抓/命中跳过重抓窗口)，本次添加不会经过 WebSocket
    console.log(`[AddOneDialog] 后端同步返回已就绪数据，直接展示: nodeId=${res.id}, isActivity=${res.typeApp.isActivity}`)
    bookmarkStore.addNode(res, parentKey)
  } else {
    console.log(`[AddOneDialog] 后端返回 LOADING 占位，等待 WebSocket 推送解析结果: nodeId=${res.id}`)
    bookmarkStore.addLoading(res, parentKey)
    // 解析结果靠 WebSocket 推送，是尽力而为的；超时未收到就主动重新拉取桌面布局兜底，避免卡死在 loading
    bookmarkStore.watchForResolution(res.id)
  }
  if (dirId) placeIntoFolder(res.id, dirId)
}

/**
 * 把刚添加的节点在服务端也挂到目标文件夹下。
 *
 * `addOne` / `linkOne` 一律把节点建在根目录（后端没有"建在某个文件夹里"的入参），所以归属得靠
 * 随后这次 moveNode 落库 —— 上面的 addNode/addLoading 只是本地乐观更新。失败就把这一格退回根目录：
 * 不退的话，页面上它在文件夹里、服务端在根，用户下次刷新会看到书签自己"搬了家"，而中间没有任何提示。
 */
function placeIntoFolder(nodeId: string, dirId: string) {
  // 目标文件夹如果是收起的，新书签会落进一个看不见的地方——展开它，让这次添加有可见的结果
  if (bookmarkStore.isFolderCollapsed(dirId)) bookmarkStore.toggleFolderCollapsed(dirId)
  bookmarksMoveNode(nodeId, dirId).catch((error) => {
    console.error('[AddOneDialog] 新书签移入文件夹失败', error)
    bookmarkStore.moveLocal(nodeId, ROOT_KEY, Number.MAX_SAFE_INTEGER)
    useToastStore().warning('书签已添加，但没能放进该文件夹')
  })
}

</script>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
