<template>
  <!-- h-dvh 而非 h-screen：内层是 overflow-y-auto 的滚动容器，用 100vh 会让容器比可视区高出
       一条地址栏，底部书签永远滚不出来 -->
  <div class="flex h-dvh w-full flex-col select-none">
    <CommonHeader />
    <div class="flex-1 overflow-y-auto bg-white dark:bg-slate-900">
      <div class="max-w-6xl mx-auto px-4 py-6">
        <h1 class="text-xl font-semibold text-slate-900 dark:text-slate-100 mb-4">全部书签</h1>

        <div class="flex flex-col sm:flex-row sm:items-center gap-2 sm:gap-3 mb-6">
          <label
            class="cy-input flex items-center gap-2 w-full sm:flex-1"
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
          <!-- sm:contents 让这层包裹在宽屏下消失，两个按钮重新变成外层 flex 的直接子项 -->
          <div class="flex items-center gap-2 sm:contents">
            <button type="button" class="cy-btn cy-btn-primary cy-btn-sm flex-1 sm:flex-none shrink-0" @click="openAddBookmark">
              <Icon icon="mdi:plus" class="size-4" />
              新增书签
            </button>
            <button
              type="button"
              class="cy-btn cy-btn-outline cy-btn-sm flex-1 sm:flex-none shrink-0"
              @click="openCreateFolderPicker">
              <Icon icon="mdi:folder-plus-outline" class="size-4" />
              新建文件夹
            </button>
          </div>
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
            <div ref="folderGridRef" class="grid justify-center gap-4" :style="folderGridStyle">
              <div v-for="(column, i) in folderColumns" :key="i" class="flex flex-col gap-4 min-w-0">
                <div
                  v-for="folder in column"
                  :key="folder.id"
                  :data-folder-card-id="folder.id"
                  class="relative transition-opacity"
                  :class="{ 'opacity-40': draggingFolderId === folder.id }">
                  <span
                    v-if="dropTargetFolderId === folder.id && dropFolderMode === 'above'"
                    class="pointer-events-none absolute inset-x-0 -top-2 h-0.5 rounded-full bg-primary z-10" />
                  <BookmarkFolderCard
                    :name="folder.name"
                    :color="folder.color"
                    :initial-collapsed="folder.collapsed"
                    :is-root="folder.isRoot"
                    :folder-id="folder.id"
                    :children="folder.children"
                    @edit="openEditModal"
                    @share="onShareFolder" />
                  <span
                    v-if="dropTargetFolderId === folder.id && dropFolderMode === 'below'"
                    class="pointer-events-none absolute inset-x-0 -bottom-2 h-0.5 rounded-full bg-primary z-10" />
                </div>
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
              class="group flex items-center gap-3 py-2 px-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
              :class="{ 'bg-slate-100 dark:bg-slate-800/60': contextMenuItemId === item.id }"
              @click="recordOpen(item)"
              @contextmenu.prevent="openMyResultMenu($event.x, $event.y, item)">
              <BookmarkLogo :value="item.typeApp!" size="S" />
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
              <!-- 触屏没有右键，这个按钮是同一份菜单的第二个触发器 -->
              <button
                type="button"
                class="shrink-0 reveal-on-hover p-1 -m-1 text-slate-400 hover:text-primary transition-opacity"
                aria-label="书签操作"
                @click.stop.prevent="openMyResultMenuFromButton($event, item)">
                <Icon icon="mdi:dots-horizontal" class="size-4" />
              </button>
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
          <!-- 本地/IP 类书签后端根本不抓（ScrapeTargetGuard），摆出按钮只会点了没反应 -->
          <button
            v-if="canRefetchEditing"
            type="button"
            class="cy-btn cy-btn-ghost mr-auto"
            :disabled="editRefetchRequested"
            @click="requestRefetch">
            {{ editRefetchRequested ? '等待后台重新抓取' : '重新抓取' }}
          </button>
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
            <BookmarkLogo :value="item.typeApp!" size="S" />
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
import { BookmarkLinkType, HomeItemType, ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import {
  bookmarksSearch,
  bookmarksLinkOne,
  bookmarksDel,
  bookmarksUpdate,
  bookmarksPin,
  bookmarksRefetch,
  bookmarksCreateDir,
  bookmarksRecordOpen,
  bookmarksSort,
} from '@api'
import { useDebounceFn, useBreakpoints, breakpointsTailwind } from '@vueuse/core'
import ContextMenu from '@imengyu/vue3-context-menu'
import { Icon } from '@iconify/vue'
import { externalHref } from '@utils'
import { draggable, dropTargetForElements, monitorForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/combine'

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
const { moveToFolder, hasMoveTargets } = useBookmarkMove()
const { $track } = useNuxtApp()

const isLoadingBookmarks = ref(false)
const contextMenuItemId = ref<string | null>(null)
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
    { id: ROOT_CARD_ID, name: '根目录', color: null, collapsed: undefined, isRoot: true, children: rootBookmarks },
    ...dirs.map((dir) => ({
      id: dir.id,
      name: dir.name || '文件夹',
      color: dir.color,
      collapsed: dir.collapsed,
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
// 上限写成 min(420px, 100%) 而非 420px：非弹性轨道会取到它的 max 值，窄屏下 420px 的轨道
// 直接把 343px 宽的容器撑出横向滚动条
const folderGridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${columnCount.value}, minmax(0, min(420px, 100%)))`,
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
    // 折叠的卡片只剩标题那一行，按子项数估高会把它当成一张长卡片，整栏就此空出一大截
    heights[shortest] += (folder.collapsed === true || bookmarkStore.isFolderCollapsed(folder.id)) ? 1 : folder.children.length + 1
  }
  return columns
})

// ── 文件夹卡片拖动排序 ──
// folderColumns 是按「当前最矮列」贪心分配的瀑布流布局，可视化的列/行位置和实际排序无关，
// 因此拖拽落点始终换算回 order[ROOT_KEY] 里文件夹 id 的相对顺序，而不是操作可视化坐标本身，
// 提交后交给 folderColumns 按新顺序重新分栏。
// 与 BookmarkFolderCard.vue 内部「拖书签」是两套独立的 dropTarget：靠 source.data.kind 互斥
// （该文件里的行/卡片级 dropTarget 都已排除 kind === 'folder-card' 的来源）。
// 卡片元素按 data 属性现查，不维护 id→el 的 Map：瀑布流重排会让一张卡片在两列之间「卸载+挂载」，
// 而 Vue 的 patch 按列序推进，卡片从右列挪到左列时是「先挂载新元素、后卸载旧元素」，两次 ref 回调
// 的净效果是把刚存进去的条目删掉，那张卡片从此既不能拖也不能被放。同 BookmarkFolderCard 里查行的写法。
const folderGridRef = ref<HTMLElement | null>(null)

const draggingFolderId = ref<string | null>(null)
const dropTargetFolderId = ref<string | null>(null)
const dropFolderMode = ref<'above' | 'below' | null>(null)

function classifyFolderDrop(el: HTMLElement, clientY: number): 'above' | 'below' {
  const r = el.getBoundingClientRect()
  return clientY - r.top < r.height / 2 ? 'above' : 'below'
}

function resetFolderDrag() {
  draggingFolderId.value = null
  dropTargetFolderId.value = null
  dropFolderMode.value = null
}

// 只重排文件夹 id 之间的相对顺序，根目录下穿插的普通书签保持原有相对顺序和位置不变——它们
// 从不与文件夹卡片交叉渲染，folderCards 早已把两者分开分组，交叉顺序本身没有任何可见意义。
// 拖到根目录卡片上视为「排到所有文件夹最前面」，故忽略 mode，锚定第一个文件夹之前。
function decideFolderDrop(sourceId: string, targetId: string, mode: 'above' | 'below') {
  if (sourceId === targetId) return
  const order = bookmarkStore.order[ROOT_KEY] ?? []
  const sourceIndex = order.indexOf(sourceId)
  if (sourceIndex < 0) return

  let insertIndex: number
  if (targetId === ROOT_CARD_ID) {
    const dirIds = new Set(bookmarkStore.rootNodes.filter((n) => n.type === HomeItemType.BOOKMARK_DIR).map((n) => n.id))
    const firstDirIndex = order.findIndex((id) => dirIds.has(id))
    insertIndex = firstDirIndex < 0 ? order.length : firstDirIndex
  } else {
    const targetIndex = order.indexOf(targetId)
    if (targetIndex < 0) return
    insertIndex = mode === 'below' ? targetIndex + 1 : targetIndex
  }

  const next = [...order]
  next.splice(sourceIndex, 1)
  if (sourceIndex < insertIndex) insertIndex -= 1
  next.splice(Math.max(0, Math.min(insertIndex, next.length)), 0, sourceId)
  bookmarkStore.reorderLocal(ROOT_KEY, next)
  bookmarksSort(bookmarkStore.fullOrderParams).catch((error) => console.error('[index] 文件夹排序保存失败', error))
}

let folderDndCleanup: (() => void) | null = null
let folderMonitorCleanup: (() => void) | null = null

function registerFolderDnd() {
  folderDndCleanup?.()
  folderDndCleanup = null
  const root = folderGridRef.value
  if (!root) return
  const disposers: Array<() => void> = []
  for (const el of Array.from(root.querySelectorAll<HTMLElement>('[data-folder-card-id]'))) {
    const id = el.dataset.folderCardId!
    // 根目录卡片是合成分组、不是真实节点，不可被拖动，但仍可作为放置目标（见 decideFolderDrop）
    if (id !== ROOT_CARD_ID) {
      const handle = el.querySelector<HTMLElement>('[data-folder-handle]')
      if (handle) {
        disposers.push(
          // ⚠️ draggable 的 element 必须是卡片外层 div，不能直接传把手。把手是 <Icon> 渲染出的
          // <svg>，而 SVGElement 不是 HTMLElement：`draggable="true"` 是 HTML 全局属性，浏览器
          // 只对 HTML 元素把它映射成 -webkit-user-drag，挂在 <svg> 上完全不生效，dragstart 根本
          // 不会触发；退一步说即便触发了，element adapter 里也有一句
          // `if (!(target instanceof HTMLElement)) return` 会把它丢掉。表现是文件夹卡片一动不动，
          // 且控制台无任何报错。BookmarkTreeRow 的 <a draggable="false"> 是同一个坑的另一半。
          // 限定「只能从把手起拖」改用 dragHandle 参数（它走 contains() 判定，SVG 可以）；
          // 顺带把拖拽预览从 16px 的小图标变成整张卡片。
          draggable({
            element: el,
            dragHandle: handle,
            getInitialData: () => ({ kind: 'folder-card', id }),
            onDragStart: () => (draggingFolderId.value = id),
            onDrop: resetFolderDrag,
          }),
        )
      }
    }
    disposers.push(
      dropTargetForElements({
        element: el,
        canDrop: ({ source }) => source.data.kind === 'folder-card' && source.data.id !== id,
        getData: () => ({ kind: 'folder-card', id }),
        onDrag: ({ location }) => {
          if (location.current.dropTargets[0]?.element !== el) return
          dropTargetFolderId.value = id
          dropFolderMode.value = classifyFolderDrop(el, location.current.input.clientY)
        },
        onDragLeave: () => {
          if (dropTargetFolderId.value === id) {
            dropTargetFolderId.value = null
            dropFolderMode.value = null
          }
        },
      }),
    )
  }
  folderDndCleanup = combine(...disposers)
}

function registerFolderMonitor() {
  folderMonitorCleanup?.()
  folderMonitorCleanup = monitorForElements({
    onDrop: ({ source, location }) => {
      resetFolderDrag()
      if (source.data.kind !== 'folder-card') return
      const target = location.current.dropTargets[0]
      if (!target) return
      const sourceId = String(source.data.id)
      const targetId = String(target.data.id)
      if (sourceId === targetId) return
      const mode = classifyFolderDrop(target.element as HTMLElement, location.current.input.clientY)
      decideFolderDrop(sourceId, targetId, mode)
    },
  })
}

// 盯 folderColumns 而不是卡片 id 列表：卡片元素是「按列」渲染的，一张卡片换列就是一次
// 卸载 + 挂载，注册就此指向脱离文档的旧元素。而换列的触发条件里，id 列表大多没变——
// 窗口跨 md/xl 断点（列数变）、某个文件夹增删一条书签（贪心分栏的高度变）都会重排分栏，
// 只盯 id 列表这两种情况都收不到通知，表现是「刚往文件夹里拖了个书签，文件夹就拖不动了」。
// folderColumns 每次求值都返回新数组，任何一次重算都会触发，同时覆盖了 id 列表变化本身。
// 网格元素本身也一并盯住：搜索时整个网格被 v-else 卸载，清空搜索词后卡片 id 列表原封不动，
// 表现为「搜过一次之后文件夹就拖不动了」。骨架屏/空态切回来也是同一条路径。
watch([folderGridRef, folderColumns], () => nextTick(registerFolderDnd))
onMounted(() =>
  nextTick(() => {
    registerFolderDnd()
    registerFolderMonitor()
  }),
)
onBeforeUnmount(() => {
  folderDndCleanup?.()
  folderMonitorCleanup?.()
})

function onShareFolder(folderId: string) {
  navigateTo(`/share/edit?folderId=${encodeURIComponent(folderId)}`)
}

// ── 搜索区工具栏：新增书签 / 新建文件夹 ──
function openAddBookmark() {
  // 不带目标文件夹：工具栏这一路永远落到根目录（文件夹卡片菜单里的「添加书签」才带落点）
  useSysStore().openAddBookmarkDialog()
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
    $track('folder-create')
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

// 用户已持有的 canonical pageId 集合，用于从「建议书签」中排除已添加的项。
// 必须是 pageId：BookmarkSearchVO.id 就是 page.id，拿 bookmarkId(本用户关联ID) 去比永远不相等。
const ownedPageIds = computed(() => {
  const ids = new Set<string>()
  for (const node of Object.values(bookmarkStore.nodes)) {
    if (node.type === HomeItemType.BOOKMARK && node.typeApp?.pageId) ids.add(node.typeApp.pageId)
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
    suggestResults.value = ((res as BookmarkSearchVO[]) || []).filter((item) => !ownedPageIds.value.has(item.id))
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
  bookmarksRecordOpen(item.typeApp.bookmarkId).catch(() => {})
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
    await bookmarksPin(item.typeApp.bookmarkId, next)
    bookmarkStore.setPinnedLocal(item.id, next)
    useToastStore().success(next ? '已置顶' : '已取消置顶')
  } catch (error) {
    console.error('[index] 置顶状态切换失败', error)
  }
}

/** 右键与行尾 ⋯ 共用同一份菜单定义 —— 复制一份的话，以后加菜单项必然漏改一处 */
function openMyResultMenu(x: number, y: number, item: UserLayoutNodeVO) {
  if (!item.typeApp) return
  contextMenuItemId.value = item.id
  ContextMenu.showContextMenu({
    items: [
      { label: '修改', icon: h(Icon, { icon: 'mdi:pencil', class: 'size-4' }), onClick: () => openEditModal(item) },
      {
        label: item.typeApp.pinned ? '取消置顶' : '置顶',
        icon: h(Icon, { icon: item.typeApp.pinned ? 'mdi:pin-off' : 'mdi:pin', class: 'size-4' }),
        onClick: () => toggleMyResultPinned(item),
      },
      {
        label: '移动到…',
        icon: h(Icon, { icon: 'mdi:folder-move-outline', class: 'size-4' }),
        hidden: !hasMoveTargets.value,
        onClick: () => moveToFolder(item),
      },
      {
        label: '删除',
        icon: h(Icon, { icon: 'mdi:trash-can', class: 'size-4' }),
        customClass: 'bookmark-context-menu-delete',
        divided: 'up',
        onClick: () => delMyResult(item),
      },
    ],
    x,
    y,
    customClass: 'bookmark-context-menu',
    onClose: () => {
      if (contextMenuItemId.value === item.id) contextMenuItemId.value = null
    },
  })
}

/** 按钮触发时用按钮自身的位置，而不是点击坐标 —— 后者在触屏上会把菜单顶到手指底下 */
function openMyResultMenuFromButton(e: MouseEvent, item: UserLayoutNodeVO) {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  openMyResultMenu(rect.right, rect.bottom, item)
}

// ── 修改书签弹窗 ──
const editDialogRef = ref<HTMLDialogElement | null>(null)
const editingNode = ref<UserLayoutNodeVO | null>(null)
const editForm = reactive({ title: '', description: '' })
const editSaving = ref(false)

// 已经请求过重新抓取的书签（按 bookmarkId 记）。之所以不是一个随弹窗开关重置的布尔量：
// 抓取要几十秒，用户关掉弹窗再点开同一条时任务多半还在跑，按钮若复原成可点的「重新抓取」，
// 就是在邀请他再排一次队。本轮会话内保持禁用，刷新页面即清空——那时任务早已结束。
const refetchRequestedIds = ref(new Set<string>())

const canRefetchEditing = computed(() => {
  const app = editingNode.value?.typeApp
  if (!app) return false
  return app.linkType !== BookmarkLinkType.LOCAL && app.linkType !== BookmarkLinkType.IP
})

const editRefetchRequested = computed(() => {
  const id = editingNode.value?.typeApp?.bookmarkId
  return !!id && refetchRequestedIds.value.has(id)
})

async function requestRefetch() {
  const app = editingNode.value?.typeApp
  if (!app || refetchRequestedIds.value.has(app.bookmarkId)) return
  try {
    await bookmarksRefetch(app.bookmarkId)
    // Set 是引用类型，就地 add 不会触发上面那个 computed 重算，必须换新引用
    refetchRequestedIds.value = new Set(refetchRequestedIds.value).add(app.bookmarkId)
    $track('bookmark-refetch')
    useToastStore().success('已提交重新抓取，完成后会自动更新')
  } catch (error) {
    // 失败时刻意不置位：按钮保持可点，用户还能再试一次
    console.error('[index] 提交重新抓取失败', error)
  }
}

function openEditModal(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  editingNode.value = node
  editForm.title = node.typeApp.title || ''
  editForm.description = node.typeApp.description || ''
  editDialogRef.value?.showModal()
}

function closeEditModal() {
  editDialogRef.value?.close()
  editingNode.value = null
}

async function saveEdit() {
  const node = editingNode.value
  if (!node?.typeApp || editSaving.value) return
  const title = editForm.title.trim()
  const description = editForm.description.trim()
  editSaving.value = true
  try {
    await bookmarksUpdate({ linkId: node.typeApp.bookmarkId, title, description })
    // 用刚提交的值就地更新那一格。接口只回一个布尔量（见 bookmarksUpdate 的注释），
    // 从它身上读 title/description 得到的是 undefined，等于保存一次清空一次。
    bookmarkStore.nodes[node.id] = { ...node, typeApp: { ...node.typeApp, title, description } }
    // 标题清空不等于「这条书签没有标题」，而是「交还给服务端决定」：兜底文案的优先级
    // （站点短名 → 页面标题 → 域名，还分 TILE/LIST）只存在于后端 BookmarkDisplayPolicy，
    // 本地算不出来，只能补拉一次布局把它取回来，否则这一格会一直退化成裸域名。
    if (!title) bookmarkStore.refresh('清空书签标题后取回服务端兜底文案')
    useToastStore().success('修改成功')
    closeEditModal()
  } catch (error) {
    console.error('[index] 修改书签失败', error)
  } finally {
    editSaving.value = false
  }
}
</script>

<style scoped>
/* 根节点的 select-none 会连输入框里的文字一起锁死（Safari 上尤其明显：光标能进去，但选不中、
   双击选词失效），表单元素必须单独放行。用 :deep 是因为文件夹重命名的输入框在子组件里。 */
:deep(input),
:deep(textarea) {
  user-select: text;
}
</style>
