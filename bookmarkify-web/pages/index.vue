<template>
  <div class="flex h-screen w-full flex-col">
    <CommonHeader />
    <div class="flex-1 overflow-y-auto bg-white dark:bg-slate-900">
      <div class="max-w-6xl mx-auto px-4 py-6">
        <h1 class="text-xl font-semibold text-slate-900 dark:text-slate-100 mb-4">全部书签</h1>

        <div class="flex items-center gap-3 mb-6">
          <label
            class="cy-input flex items-center gap-2 flex-1"
            :class="query ? 'cy-input-primary' : ''">
            <Icon icon="mdi:magnify" class="size-4 text-slate-400 dark:text-slate-500 shrink-0" />
            <input
              v-model="query"
              type="text"
              class="flex-1 bg-transparent focus:outline-none text-sm"
              placeholder="搜索书签标题、描述或网址..." />
            <button
              v-if="query"
              type="button"
              class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
              @click="query = ''">
              ✕
            </button>
          </label>
          <button type="button" class="cy-btn cy-btn-primary cy-btn-sm shrink-0" @click="openAddBookmark">
            <Icon icon="mdi:plus" class="size-4" />
            新增书签
          </button>
          <button type="button" class="cy-btn cy-btn-outline cy-btn-sm shrink-0" @click="openCreateFolderPicker">
            <Icon icon="mdi:folder-plus-outline" class="size-4" />
            新建文件夹
          </button>
        </div>

        <template v-if="!query">
          <template v-if="isLoadingBookmarks">
            <div class="animate-pulse space-y-0 max-w-2xl">
              <div v-for="i in 8" :key="i" class="flex items-center gap-2 py-1.5">
                <div class="size-5 rounded-full bg-slate-200 dark:bg-slate-700 shrink-0" />
                <div class="h-4 rounded bg-slate-200 dark:bg-slate-700" :style="{ width: skeletonWidth(i) }" />
              </div>
            </div>
          </template>
          <template v-else-if="bookmarkStore.rootNodes.length === 0">
            <div class="text-sm text-slate-400 dark:text-slate-500 py-10 text-center">暂无书签</div>
          </template>
          <template v-else>
            <div v-if="bookmarkStore.pinnedNodes.length" class="mb-6">
              <div class="text-xs font-semibold text-slate-400 dark:text-slate-500 mb-2">置顶</div>
              <PinnedBookmarkGrid :nodes="bookmarkStore.pinnedNodes" @edit="openEditModal" />
            </div>
            <div class="grid justify-center gap-4" :style="folderGridStyle">
              <div v-for="(column, i) in folderColumns" :key="i" class="flex flex-col gap-4 min-w-0">
                <BookmarkFolderCard
                  v-for="folder in column"
                  :key="folder.id"
                  :name="folder.name"
                  :is-root="folder.isRoot"
                  :folder-id="folder.id"
                  :children="folder.children"
                  @edit="openEditModal"
                  @share="onShareFolder" />
              </div>
            </div>
          </template>
        </template>

        <template v-else>
          <div class="mb-6">
            <div class="text-xs font-semibold text-slate-400 dark:text-slate-500 mb-2">
              我的书签 <span v-if="myResults.length">({{ myResults.length }})</span>
            </div>
            <div v-if="myResults.length === 0" class="text-sm text-slate-400 dark:text-slate-500 py-4 text-center">
              没有匹配的书签
            </div>
            <a
              v-for="item in myResults"
              :key="item.id"
              :href="externalHref(item.typeApp!.urlFull)"
              target="_blank"
              rel="noopener noreferrer"
              class="flex items-center gap-3 py-2 px-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
              @click="recordOpen(item)"
              @contextmenu.prevent="onMyResultContextMenu($event, item)">
              <BookmarkLogo :value="item.typeApp!" :size="20" />
              <div class="flex flex-col overflow-hidden flex-1">
                <span
                  class="text-sm truncate"
                  :class="
                    item.typeApp!.isActivity === false
                      ? 'text-slate-400 dark:text-slate-500'
                      : 'text-slate-700 dark:text-slate-200'
                  ">
                  {{ item.typeApp!.title || item.typeApp!.urlBase }}
                </span>
                <span class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ item.typeApp!.urlBase }}</span>
              </div>
            </a>
          </div>

          <div>
            <div class="flex items-center gap-2 text-xs font-semibold text-slate-400 dark:text-slate-500 mb-2">
              <span>建议书签</span>
              <span v-if="suggestResults.length">({{ suggestResults.length }})</span>
              <div v-if="isSuggesting" class="size-3 border-2 border-slate-300 border-t-primary rounded-full animate-spin" />
            </div>
            <div v-if="!isSuggesting && suggestResults.length === 0" class="text-sm text-slate-400 dark:text-slate-500 py-4 text-center">
              暂无来自其他用户的匹配书签
            </div>
            <div
              v-for="item in suggestResults"
              :key="item.id"
              class="flex items-center gap-3 py-2 px-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors">
              <img v-if="item.logo?.url" :src="item.logo.url" class="size-5 rounded shrink-0" />
              <div v-else class="size-5 rounded bg-slate-100 dark:bg-slate-800 center shrink-0">
                <Icon icon="mdi:earth" class="size-3 text-slate-400" />
              </div>
              <div class="flex flex-col overflow-hidden flex-1">
                <span class="text-sm text-slate-700 dark:text-slate-200 truncate">
                  {{ item.title || item.appName || item.urlHost }}
                </span>
                <span class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ item.urlHost }}</span>
              </div>
              <button
                type="button"
                class="text-xs font-semibold text-primary shrink-0"
                @click="linkSuggested(item)">
                添加
              </button>
            </div>
          </div>
        </template>
      </div>
    </div>

    <dialog ref="editDialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <div class="text-lg font-semibold text-slate-900 dark:text-slate-100 mb-4">修改书签</div>
        <!--
          页面封面：截图优先，退 og:image。按需拉取，没有就整块不渲染 ——
          留一个空的 16:9 灰框只会让"这个站抓不到图"看起来像"图挂了"。
        -->
        <img
          v-if="editCover"
          :src="editCover"
          alt=""
          loading="lazy"
          class="w-full aspect-video object-cover object-top rounded-lg mb-4 bg-slate-100 dark:bg-slate-800"
          @error="editCover = null" />
        <div class="space-y-3">
          <label class="block">
            <span class="text-sm text-slate-600 dark:text-slate-300">书签名称</span>
            <input v-model="editForm.title" type="text" maxlength="150" class="cy-input w-full mt-1" placeholder="请输入书签名称" />
          </label>
          <label class="block">
            <span class="text-sm text-slate-600 dark:text-slate-300">书签描述</span>
            <textarea
              v-model="editForm.description"
              rows="3"
              class="cy-textarea w-full mt-1"
              placeholder="请输入书签描述" />
          </label>
        </div>
        <div class="cy-modal-action">
          <button type="button" class="cy-btn cy-btn-ghost" :disabled="editSaving" @click="closeEditModal">取消</button>
          <button type="button" class="cy-btn cy-btn-primary" :disabled="editSaving" @click="saveEdit">
            {{ editSaving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button @click="closeEditModal">close</button>
      </form>
    </dialog>

    <dialog ref="createFolderDialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <div class="text-lg font-semibold text-slate-900 dark:text-slate-100 mb-4">新建文件夹</div>
        <input
          v-model="newFolderName"
          type="text"
          maxlength="30"
          class="cy-input w-full mb-4"
          placeholder="请输入文件夹名称" />
        <div class="text-xs font-semibold text-slate-400 dark:text-slate-500 mb-2">
          选择至少 2 个书签放入文件夹（已选 {{ pickedBookmarkIds.size }}）
        </div>
        <div
          v-if="pickableBookmarks.length === 0"
          class="text-sm text-slate-400 dark:text-slate-500 py-6 text-center">
          暂无可选书签
        </div>
        <div v-else class="max-h-72 overflow-y-auto rounded-lg border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-800">
          <label
            v-for="item in pickableBookmarks"
            :key="item.id"
            class="flex items-center gap-3 px-3 py-2 hover:bg-slate-50 dark:hover:bg-slate-800/50 cursor-pointer">
            <input
              type="checkbox"
              class="cy-checkbox cy-checkbox-sm"
              :checked="pickedBookmarkIds.has(item.id)"
              @change="togglePickedBookmark(item.id)" />
            <BookmarkLogo :value="item.typeApp!" :size="20" />
            <span class="text-sm text-slate-700 dark:text-slate-200 truncate flex-1">
              {{ item.typeApp!.title || item.typeApp!.urlBase }}
            </span>
          </label>
        </div>
        <div class="cy-modal-action">
          <button type="button" class="cy-btn cy-btn-ghost" :disabled="creatingFolder" @click="closeCreateFolderPicker">取消</button>
          <button
            type="button"
            class="cy-btn cy-btn-primary"
            :disabled="creatingFolder || !newFolderName.trim() || pickedBookmarkIds.size < 2"
            @click="confirmCreateFolder">
            {{ creatingFolder ? '创建中...' : '创建' }}
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button @click="closeCreateFolderPicker">close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { h } from 'vue'
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
import {
  bookmarksSearch,
  bookmarksLinkOne,
  bookmarksDel,
  bookmarksUpdate,
  bookmarksPin,
  bookmarksCover,
  bookmarksCreateDir,
  bookmarksRecordOpen,
} from '@api'
import { useDebounceFn, useBreakpoints, breakpointsTailwind } from '@vueuse/core'
import ContextMenu from '@imengyu/vue3-context-menu'
import { Icon } from '@iconify/vue'
import { externalHref } from '@utils'
import BookmarkLogo from '@/components/launchpad/cell/BookmarkLogo.vue'

definePageMeta({ middleware: 'auth' })

interface BookmarkSearchVO {
  id: string
  urlHost: string
  urlScheme: string
  appName?: string
  title?: string
  logo?: { url?: string }
}

const bookmarkStore = useBookmarkStore()

const isLoadingBookmarks = ref(false)
// 骨架屏行宽随机化，避免整齐划一显得呆板
const skeletonWidths = ['70%', '45%', '85%', '55%', '65%', '40%', '75%', '50%']
const skeletonWidth = (i: number) => skeletonWidths[(i - 1) % skeletonWidths.length]

onMounted(() => {
  if (bookmarkStore.isFresh()) return
  // 有缓存时静默后台刷新：先展示缓存中的书签，数据到了再替换，不阻塞展示
  const hasCache = bookmarkStore.rootNodes.length > 0
  if (!hasCache) isLoadingBookmarks.value = true
  bookmarkStore
    .update()
    .catch((error) => console.error('[index] 书签刷新失败', error))
    .finally(() => {
      isLoadingBookmarks.value = false
    })
})

// 按文件夹分组为卡片：根目录下未归入任何文件夹的书签单独作为「根目录」卡片，其余每个顶层文件夹各一张卡片
const ROOT_CARD_ID = '__root_card__'
const folderCards = computed(() => {
  const rootBookmarks = bookmarkStore.rootNodes.filter((node) => node.type !== HomeItemType.BOOKMARK_DIR)
  const dirs = bookmarkStore.rootNodes.filter((node) => node.type === HomeItemType.BOOKMARK_DIR)
  return [
    { id: ROOT_CARD_ID, name: '根目录', isRoot: true, children: rootBookmarks },
    ...dirs.map((dir) => ({
      id: dir.id,
      name: dir.name || '文件夹',
      isRoot: false,
      children: dir.children ?? [],
    })),
  ]
})

// 卡片宽度固定为 420px，列数随窗口宽度变化，但列宽和列间距保持固定，不随窗口拉伸
const breakpoints = useBreakpoints(breakpointsTailwind)
const isXl = breakpoints.greaterOrEqual('xl')
const isMd = breakpoints.between('md', 'xl')
const maxColumnCount = computed(() => (isXl.value ? 3 : isMd.value ? 2 : 1))
// 卡片数少于最大列数时按实际数量取列，避免渲染出占位的空列把整行挤偏
const columnCount = computed(() => Math.max(1, Math.min(maxColumnCount.value, folderCards.value.length)))

// 用 grid 而非 flex：轨道宽度由 minmax(0, 420px) 决定，与内容无关，
// 各列必定等宽（flex 项目的 min-width:auto 会被超长行撑开，导致列宽不一致）
const folderGridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${columnCount.value}, minmax(0, 420px))`,
}))

// 按「已放入子项数量」贪心分配到最短的一列，模拟瀑布流的高度均衡效果
const folderColumns = computed(() => {
  const count = columnCount.value
  const columns: (typeof folderCards.value)[number][][] = Array.from({ length: count }, () => [])
  const heights = new Array(count).fill(0)
  for (const folder of folderCards.value) {
    let shortest = 0
    for (let i = 1; i < count; i++) {
      if (heights[i]! < heights[shortest]!) shortest = i
    }
    columns[shortest]!.push(folder)
    heights[shortest] += folder.children.length + 1
  }
  return columns
})

function onShareFolder(folderId: string) {
  navigateTo(`/share/edit?folderId=${encodeURIComponent(folderId)}`)
}

// ── 搜索区工具栏：新增书签 / 新建文件夹 ──
function openAddBookmark() {
  useSysStore().addBookmarkDialogVisible = true
}

// 后端 /bookmark/createDir 只支持「合并至少 2 个已有书签生成新文件夹」，不支持创建空文件夹，
// 因此这里让用户从根目录下的书签中挑选 ≥2 个再创建
const createFolderDialogRef = ref<HTMLDialogElement | null>(null)
const newFolderName = ref('')
const pickedBookmarkIds = ref(new Set<string>())
const creatingFolder = ref(false)

const pickableBookmarks = computed(() =>
  bookmarkStore.rootNodes.filter((node) => node.type === HomeItemType.BOOKMARK && node.typeApp),
)

function togglePickedBookmark(id: string) {
  const next = new Set(pickedBookmarkIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  pickedBookmarkIds.value = next
}

function openCreateFolderPicker() {
  newFolderName.value = ''
  pickedBookmarkIds.value = new Set()
  createFolderDialogRef.value?.showModal()
}

function closeCreateFolderPicker() {
  createFolderDialogRef.value?.close()
}

async function confirmCreateFolder() {
  const name = newFolderName.value.trim()
  const ids = [...pickedBookmarkIds.value]
  if (!name || ids.length < 2 || creatingFolder.value) return
  creatingFolder.value = true
  try {
    await bookmarksCreateDir(ids, name, 0)
    useToastStore().success('文件夹创建成功')
    closeCreateFolderPicker()
    await bookmarkStore.update()
  } catch (error) {
    console.error('[index] 新建文件夹失败', error)
  } finally {
    creatingFolder.value = false
  }
}

const query = ref('')
const suggestResults = ref<BookmarkSearchVO[]>([])
const isSuggesting = ref(false)

// 用户自己的书签：全部已加载在本地 store，直接客户端过滤即可，无需请求
const myResults = computed<UserLayoutNodeVO[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return []
  return Object.values(bookmarkStore.nodes).filter((node) => {
    if (node.type !== HomeItemType.BOOKMARK || !node.typeApp) return false
    const app = node.typeApp
    return (
      app.title?.toLowerCase().includes(q) ||
      app.description?.toLowerCase().includes(q) ||
      app.urlBase?.toLowerCase().includes(q)
    )
  })
})

// 用户已持有的书签 id 集合，用于从「建议书签」中排除已添加的项
const ownedBookmarkIds = computed(() => {
  const ids = new Set<string>()
  for (const node of Object.values(bookmarkStore.nodes)) {
    if (node.type === HomeItemType.BOOKMARK && node.typeApp?.bookmarkId) ids.add(node.typeApp.bookmarkId)
  }
  return ids
})

const fetchSuggestions = useDebounceFn(async (q: string) => {
  if (!q) {
    suggestResults.value = []
    isSuggesting.value = false
    return
  }
  isSuggesting.value = true
  try {
    const res = await bookmarksSearch(q)
    // 忽略过期请求的结果
    if (q !== query.value.trim()) return
    suggestResults.value = ((res as BookmarkSearchVO[]) || []).filter((item) => !ownedBookmarkIds.value.has(item.id))
  } catch (e) {
    console.error(e)
  } finally {
    if (q === query.value.trim()) isSuggesting.value = false
  }
}, 500)

watch(query, (val) => {
  const q = val.trim()
  if (!q) {
    suggestResults.value = []
    isSuggesting.value = false
    return
  }
  fetchSuggestions(q)
})

async function linkSuggested(item: BookmarkSearchVO) {
  // 错误提示由 http.ts 统一弹；这里接住是为了不留 unhandled rejection，
  // 并且失败时不要把条目从建议列表里摘掉（摘掉会让用户以为已经加成功了）
  try {
    const res = await bookmarksLinkOne(item.id)
    bookmarkStore.addNode(res)
    suggestResults.value = suggestResults.value.filter((i) => i.id !== item.id)
    useToastStore().success('添加成功')
  } catch (error) {
    console.error('[index] 关联书签失败', error)
  }
}

function recordOpen(item: UserLayoutNodeVO) {
  if (!item.typeApp) return
  bookmarksRecordOpen(item.typeApp.bookmarkUserLinkId).catch(() => {})
}

// ── 「我的书签」搜索结果右键菜单：修改 / 删除 ──
async function delMyResult(item: UserLayoutNodeVO) {
  try {
    await useConfirmStore().confirm(`确定删除书签「${item.typeApp?.title || item.typeApp?.urlBase}」吗？`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await bookmarksDel([item.id])
    bookmarkStore.removeNode(item.id)
    useToastStore().success('删除成功')
  } catch (error) {
    console.error('[index] 删除书签失败', error)
  }
}

async function toggleMyResultPinned(item: UserLayoutNodeVO) {
  if (!item.typeApp) return
  const next = !item.typeApp.pinned
  try {
    await bookmarksPin(item.typeApp.bookmarkUserLinkId, next)
    bookmarkStore.setPinnedLocal(item.id, next)
    useToastStore().success(next ? '已置顶' : '已取消置顶')
  } catch (error) {
    console.error('[index] 置顶状态切换失败', error)
  }
}

function onMyResultContextMenu(e: MouseEvent, item: UserLayoutNodeVO) {
  if (!item.typeApp) return
  ContextMenu.showContextMenu({
    items: [
      { label: '修改', icon: h(Icon, { icon: 'mdi:pencil', class: 'size-4' }), onClick: () => openEditModal(item) },
      {
        label: item.typeApp.pinned ? '取消置顶' : '置顶',
        icon: h(Icon, { icon: item.typeApp.pinned ? 'mdi:pin-off' : 'mdi:pin', class: 'size-4' }),
        onClick: () => toggleMyResultPinned(item),
      },
      { label: '删除', icon: h(Icon, { icon: 'mdi:trash-can', class: 'size-4' }), onClick: () => delMyResult(item) },
    ],
    x: e.x,
    y: e.y,
  })
}

// ── 修改书签弹窗 ──
const editDialogRef = ref<HTMLDialogElement | null>(null)
const editingNode = ref<UserLayoutNodeVO | null>(null)
const editForm = reactive({ title: '', description: '' })
const editSaving = ref(false)
/** 封面按需拉取，见 bookmarksCover。null = 没有封面，此时整块不渲染，不占位 */
const editCover = ref<string | null>(null)

function openEditModal(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  editingNode.value = node
  editForm.title = node.typeApp.title || ''
  editForm.description = node.typeApp.description || ''
  editCover.value = null
  editDialogRef.value?.showModal()

  // 封面是锦上添花：拿不到就当没有，不弹错、不挡住改标题这件正事
  const linkId = node.typeApp.bookmarkUserLinkId
  bookmarksCover(linkId)
    .then((url) => {
      // 用户可能已经关掉弹窗又点开了另一条，晚到的响应不能盖到新的上面
      if (editingNode.value?.typeApp?.bookmarkUserLinkId === linkId) editCover.value = url
    })
    .catch(() => {})
}

function closeEditModal() {
  editDialogRef.value?.close()
  editingNode.value = null
  editCover.value = null
}

async function saveEdit() {
  const node = editingNode.value
  if (!node?.typeApp || editSaving.value) return
  editSaving.value = true
  try {
    const res = await bookmarksUpdate({
      linkId: node.typeApp.bookmarkUserLinkId,
      title: editForm.title.trim(),
      description: editForm.description.trim(),
    })
    bookmarkStore.nodes[node.id] = { ...node, typeApp: { ...node.typeApp, title: res.title, description: res.description } }
    useToastStore().success('修改成功')
    closeEditModal()
  } catch (error) {
    console.error('[index] 修改书签失败', error)
  } finally {
    editSaving.value = false
  }
}
</script>
