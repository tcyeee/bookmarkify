<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div>
      <h3 class="text-xl font-semibold">{{ $t('bookmarkLibrary.title') }}</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ $t('bookmarkLibrary.desc') }}</p>
    </div>

    <div class="flex gap-6 items-start">
      <!-- 文件夹侧栏 -->
      <aside class="w-44 shrink-0 space-y-1">
        <button
          type="button"
          class="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left transition-colors"
          :class="
            selectedFolderId === null
              ? 'bg-sky-50 dark:bg-sky-950/40 text-sky-600 dark:text-sky-300 font-medium'
              : 'text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/50'
          "
          @click="selectFolder(null)">
          <Icon icon="mdi:bookmark-multiple-outline" class="size-4 shrink-0" />
          <span class="truncate">{{ $t('bookmarkLibrary.folderPanel.all') }}</span>
        </button>

        <div class="pt-2 mt-2 border-t border-slate-100 dark:border-slate-800 space-y-1">
          <!-- 根目录：不是真实的文件夹节点（没有 id、不能重命名/删除），用紫色与普通文件夹区分 -->
          <button
            type="button"
            class="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left transition-colors"
            :class="
              selectedFolderId === ROOT_KEY
                ? 'bg-violet-50 dark:bg-violet-950/40 text-violet-600 dark:text-violet-300 font-medium'
                : 'text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/50'
            "
            @click="selectFolder(ROOT_KEY)">
            <Icon icon="mdi:folder-home" class="size-4 shrink-0 text-violet-500" />
            <span class="flex-1 truncate">{{ $t('bookmarkLibrary.folderPanel.root') }}</span>
            <span class="text-xs text-slate-400 shrink-0">{{ rootBookmarkCount }}</span>
          </button>

          <button
            v-for="folder in folders"
            :key="folder.id"
            type="button"
            class="w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left transition-colors"
            :class="
              selectedFolderId === folder.id
                ? 'bg-sky-50 dark:bg-sky-950/40 text-sky-600 dark:text-sky-300 font-medium'
                : 'text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/50'
            "
            @click="selectFolder(folder.id)">
            <Icon icon="mdi:folder" class="size-4 shrink-0 text-amber-500" />
            <span class="flex-1 truncate">{{ folder.name || $t('bookmarkLibrary.folderPanel.unnamed') }}</span>
            <span class="text-xs text-slate-400 shrink-0">{{ bookmarkStore.order[folder.id]?.length ?? 0 }}</span>
          </button>
        </div>
      </aside>

      <div class="flex-1 min-w-0 space-y-4">
        <!-- 当前文件夹：整体操作（重命名/删除） -->
        <div
          v-if="selectedFolderId"
          class="flex items-center justify-between gap-3 rounded-xl border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/50 px-4 py-2.5">
          <div class="flex items-center gap-2 min-w-0 text-sm text-slate-600 dark:text-slate-300">
            <Icon
              :icon="isRootFolder ? 'mdi:folder-home' : 'mdi:folder'"
              class="size-4 shrink-0"
              :class="isRootFolder ? 'text-violet-500' : 'text-amber-500'" />
            <input
              v-if="renamingFolder"
              ref="renameInputRef"
              v-model="renameValue"
              type="text"
              maxlength="30"
              class="cy-input cy-input-bordered cy-input-xs"
              @keydown.enter="submitRenameFolder"
              @keydown.esc.stop="cancelRenameFolder"
              @blur="submitRenameFolder" />
            <span v-else class="font-medium truncate">{{ selectedFolderLabel }}</span>
            <span class="text-slate-400 shrink-0">· {{ $t('bookmarkLibrary.folderPanel.itemCount', { count: folderBookmarks.length }) }}</span>
          </div>
          <div v-if="!isRootFolder" class="flex items-center gap-1.5 shrink-0">
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-xs" @click="startRenameFolder">
              <Icon icon="mdi:pencil-outline" class="size-3.5" />
              {{ $t('bookmarkLibrary.folderPanel.rename') }}
            </button>
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-xs text-rose-500" @click="deleteFolder">
              <Icon icon="mdi:trash-can-outline" class="size-3.5" />
              {{ $t('bookmarkLibrary.folderPanel.delete') }}
            </button>
          </div>
        </div>

        <!-- 工具栏：搜索 + 筛选 -->
        <div class="flex flex-wrap items-center gap-3">
          <div class="relative flex-1 min-w-[200px]">
            <Icon icon="mdi:magnify" class="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-slate-400" />
            <input
              v-model="keyword"
              type="text"
              class="cy-input cy-input-bordered cy-input-sm w-full pl-9"
              :placeholder="$t('bookmarkLibrary.searchPlaceholder')" />
          </div>
          <div v-if="!selectedFolderId" class="flex items-center gap-2">
            <button
              v-for="f in filters"
              :key="f.value"
              type="button"
              class="cy-btn cy-btn-sm"
              :class="filterMode === f.value ? 'cy-btn-accent' : 'cy-btn-ghost'"
              @click="setFilter(f.value)">
              {{ f.label }}
            </button>
          </div>
        </div>

        <!-- 一键整理：仅在「全部书签」视图提供，作用范围始终是账号下的全部书签而非当前页 -->
        <div
          v-if="!selectedFolderId"
          class="flex flex-wrap items-center gap-2 rounded-xl border border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-900/50 px-3 py-2">
          <span class="text-xs text-slate-400 dark:text-slate-500 mr-1">{{ $t('bookmarkLibrary.cleanup.label') }}</span>
          <button type="button" class="cy-btn cy-btn-outline cy-btn-sm" :disabled="!!cleanupBusy" @click="cleanInvalid">
            <Icon
              :icon="cleanupBusy === 'invalid' ? 'mdi:loading' : 'mdi:link-variant-off'"
              class="size-4"
              :class="{ 'animate-spin': cleanupBusy === 'invalid' }" />
            {{ $t('bookmarkLibrary.cleanup.invalid') }}
          </button>
          <button type="button" class="cy-btn cy-btn-outline cy-btn-sm" :disabled="!!cleanupBusy" @click="mergeDuplicates">
            <Icon
              :icon="cleanupBusy === 'duplicate' ? 'mdi:loading' : 'mdi:call-merge'"
              class="size-4"
              :class="{ 'animate-spin': cleanupBusy === 'duplicate' }" />
            {{ $t('bookmarkLibrary.cleanup.duplicate') }}
          </button>
        </div>

        <!-- 列表 -->
        <div class="rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
          <div
            class="flex items-center gap-3 px-4 py-2.5 bg-slate-50 dark:bg-slate-800/60 border-b border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
            <input type="checkbox" class="cy-checkbox cy-checkbox-sm" :checked="allChecked" :disabled="displayItems.length === 0" @change="toggleSelectAll" />
            <span class="flex-1">{{ $t('bookmarkLibrary.columns.bookmark') }}</span>
            <span v-if="!selectedFolderId" class="w-32 shrink-0 text-center hidden sm:block">{{ $t('bookmarkLibrary.columns.folder') }}</span>
            <span class="w-16 shrink-0 text-center">{{ $t('bookmarkLibrary.columns.status') }}</span>
            <span class="w-8 shrink-0" />
          </div>

          <div v-if="loading && !selectedFolderId" class="py-16 text-center text-sm text-slate-400 dark:text-slate-500">
            <Icon icon="mdi:loading" class="size-6 animate-spin mx-auto mb-2" />
            {{ $t('bookmarkLibrary.loading') }}
          </div>
          <div v-else-if="displayItems.length === 0" class="py-16 text-center text-sm text-slate-400 dark:text-slate-500">
            {{ emptyText }}
          </div>
          <div v-else class="max-h-[28rem] overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
            <label
              v-for="item in displayItems"
              :key="item.layoutNodeId ?? item.bookmarkUserLinkId"
              class="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-800/50 cursor-pointer transition-colors">
              <input
                type="checkbox"
                class="cy-checkbox cy-checkbox-sm"
                :checked="isSelected(item)"
                @change="toggleOne(item)" />
              <BookmarkLogo :value="item" :size="28" />
              <div class="flex-1 min-w-0">
                <p class="text-sm font-medium truncate text-slate-700 dark:text-slate-200">{{ item.title || item.urlBase }}</p>
                <a
                  :href="item.urlFull"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="block text-xs text-slate-400 dark:text-slate-500 truncate hover:text-sky-500 hover:underline"
                  @click.stop
                  >{{ item.urlFull }}</a
                >
              </div>
              <span v-if="!selectedFolderId" class="w-32 shrink-0 text-center text-xs truncate text-slate-500 dark:text-slate-400 hidden sm:block">{{
                item.folderName || '—'
              }}</span>
              <span class="w-16 shrink-0 text-center">
                <span v-if="item.isActivity === false" class="cy-badge cy-badge-error cy-badge-sm text-white">{{ $t('bookmarkLibrary.status.invalid') }}</span>
              </span>
              <button
                type="button"
                class="w-8 shrink-0 cy-btn cy-btn-ghost cy-btn-xs cy-btn-circle"
                :title="$t('bookmarkLibrary.deleteOne')"
                @click.prevent="deleteOne(item)">
                <Icon icon="mdi:trash-can-outline" class="size-4 text-slate-400 hover:text-rose-500" />
              </button>
            </label>
          </div>
        </div>

        <!-- 分页（仅全部书签视图，按文件夹浏览时不分页） -->
        <div v-if="!selectedFolderId" class="flex items-center justify-between text-sm text-slate-500 dark:text-slate-400">
          <div class="flex items-center gap-3">
            <span>{{ $t('bookmarkLibrary.pagination.total', { total: page?.total ?? 0 }) }}</span>
            <label class="flex items-center gap-1.5 shrink-0">
              <span class="whitespace-nowrap">{{ $t('bookmarkLibrary.pagination.pageSize') }}</span>
              <select v-model.number="pageSize" class="cy-select cy-select-bordered cy-select-xs">
                <option v-for="size in pageSizeOptions" :key="size" :value="size">{{ size }}</option>
              </select>
            </label>
          </div>
          <div v-if="totalPages > 1" class="flex items-center gap-2">
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" :disabled="currentPage <= 1" @click="goToPage(currentPage - 1)">
              <Icon icon="mdi:chevron-left" class="size-4" />
            </button>
            <span>{{ $t('bookmarkLibrary.pagination.pageOf', { current: currentPage, total: totalPages }) }}</span>
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" :disabled="currentPage >= totalPages" @click="goToPage(currentPage + 1)">
              <Icon icon="mdi:chevron-right" class="size-4" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 批量操作栏 -->
    <Transition name="fade-fast">
      <div
        v-if="selectedIds.size > 0"
        class="sticky bottom-4 flex items-center justify-between gap-4 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-lg px-4 py-3">
        <span class="text-sm text-slate-600 dark:text-slate-300">{{ $t('bookmarkLibrary.selectedCount', { count: selectedIds.size }) }}</span>
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="cy-btn cy-btn-outline cy-btn-sm"
            :disabled="selectedIds.size < 2"
            :title="selectedIds.size < 2 ? $t('bookmarkLibrary.createCollectionHint') : ''"
            @click="openCreateDialog">
            <Icon icon="mdi:folder-plus-outline" class="size-4" />
            {{ $t('bookmarkLibrary.createCollection') }}
          </button>
          <button type="button" class="cy-btn cy-btn-error cy-btn-sm" @click="batchDelete">
            <Icon icon="mdi:trash-can-outline" class="size-4" />
            {{ $t('bookmarkLibrary.batchDelete') }}
          </button>
        </div>
      </div>
    </Transition>

    <!-- 创建集合弹窗 -->
    <dialog ref="createDialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-sm">
        <h3 class="text-base font-semibold text-slate-800 dark:text-slate-100">{{ $t('bookmarkLibrary.createDialog.title') }}</h3>
        <input
          ref="createNameInputRef"
          v-model="newFolderName"
          type="text"
          maxlength="30"
          class="cy-input cy-input-bordered w-full mt-4"
          :placeholder="$t('bookmarkLibrary.createDialog.placeholder')"
          @keydown.enter="confirmCreateCollection" />
        <div class="cy-modal-action mt-6">
          <button type="button" class="cy-btn cy-btn-ghost" @click="closeCreateDialog">{{ $t('bookmarkLibrary.createDialog.cancel') }}</button>
          <button type="button" class="cy-btn cy-btn-accent" :disabled="!newFolderName.trim()" @click="confirmCreateCollection">
            {{ $t('bookmarkLibrary.createDialog.confirm') }}
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button>close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksList, bookmarksDel, bookmarksCreateDir, bookmarksRenameDir } from '@api'
import { HomeItemType, ROOT_KEY } from '@typing'
import type * as t from '@typing'
import { useDebounceFn } from '@vueuse/core'

type FilterMode = 'all' | 'duplicate' | 'invalid'
type CleanupKind = 'invalid' | 'duplicate'

const { t: translate } = useI18n()
const bookmarkStore = useBookmarkStore()
const toastStore = useToastStore()

const pageSizeOptions = [10, 20, 50, 100]

const keyword = ref('')
const filterMode = ref<FilterMode>('all')
const currentPage = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const page = ref<t.BookmarkPage<t.BookmarkShow> | null>(null)
// 以 layoutNodeId 作为选中集合的键：这是批量删除/创建集合两个后端接口实际接受的 ID
const selectedIds = ref(new Set<string>())
// 一键整理正在执行的种类，null 表示空闲；两个整理动作互斥
const cleanupBusy = ref<CleanupKind | null>(null)

const createDialogRef = ref<HTMLDialogElement | null>(null)
const createNameInputRef = ref<HTMLInputElement | null>(null)
const newFolderName = ref('')

// 文件夹侧栏：文件夹层级来自前端已加载的书签树（bookmarkStore），无需额外请求；
// null 表示「全部书签」，走原有的后端分页/搜索/筛选；ROOT_KEY 表示「根目录」——
// 它是 order 里的虚拟父键而非真实节点，因此没有重命名/删除操作
const selectedFolderId = ref<string | null>(null)
const renamingFolder = ref(false)
const renameValue = ref('')
const renameInputRef = ref<HTMLInputElement | null>(null)

const filters = computed(() => [
  { value: 'all' as FilterMode, label: translate('bookmarkLibrary.filters.all') },
  { value: 'duplicate' as FilterMode, label: translate('bookmarkLibrary.filters.duplicate') },
  { value: 'invalid' as FilterMode, label: translate('bookmarkLibrary.filters.invalid') },
])

const folders = computed(() => bookmarkStore.rootNodes.filter((n) => n.type === HomeItemType.BOOKMARK_DIR))
const isRootFolder = computed(() => selectedFolderId.value === ROOT_KEY)
const selectedFolder = computed(() =>
  selectedFolderId.value && !isRootFolder.value ? (bookmarkStore.nodes[selectedFolderId.value] ?? null) : null,
)
const selectedFolderLabel = computed(() =>
  isRootFolder.value
    ? translate('bookmarkLibrary.folderPanel.root')
    : selectedFolder.value?.name || translate('bookmarkLibrary.folderPanel.unnamed'),
)
// 侧栏计数：普通文件夹沿用 order 长度，根目录只数书签（否则会把文件夹/功能位也算进去）
const rootBookmarkCount = computed(() => bookmarkStore.childrenOf(ROOT_KEY).filter((n) => n.type === HomeItemType.BOOKMARK).length)

// 文件夹内书签：直接取自本地树，typeApp 本身就是 BookmarkShow；覆盖 layoutNodeId 为节点 id，
// 与「全部书签」视图的选中/删除逻辑（nodeKey）保持同一套 key
const folderBookmarks = computed<t.BookmarkShow[]>(() =>
  selectedFolderId.value
    ? bookmarkStore
        .childrenOf(selectedFolderId.value)
        .filter((n) => n.type === HomeItemType.BOOKMARK && n.typeApp)
        .map((n) => ({ ...n.typeApp!, layoutNodeId: n.id }))
    : [],
)

const filteredFolderBookmarks = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return folderBookmarks.value
  return folderBookmarks.value.filter(
    (b) => (b.title || '').toLowerCase().includes(kw) || (b.urlFull || '').toLowerCase().includes(kw) || (b.urlBase || '').toLowerCase().includes(kw),
  )
})

const records = computed(() => page.value?.records ?? [])
// 统一列表数据源：选中文件夹时取本地文件夹内容，否则沿用原有的后端分页结果
const displayItems = computed<t.BookmarkShow[]>(() => (selectedFolderId.value ? filteredFolderBookmarks.value : records.value))
const totalPages = computed(() => (page.value ? Math.max(1, Math.ceil(page.value.total / pageSize.value)) : 1))
const allChecked = computed(() => displayItems.value.length > 0 && displayItems.value.every((r) => isSelected(r)))

const emptyText = computed(() => {
  if (isRootFolder.value) return translate('bookmarkLibrary.folderPanel.rootEmpty')
  if (selectedFolderId.value) return translate('bookmarkLibrary.folderPanel.empty')
  if (filterMode.value === 'duplicate') return translate('bookmarkLibrary.empty.duplicate')
  if (filterMode.value === 'invalid') return translate('bookmarkLibrary.empty.invalid')
  return translate('bookmarkLibrary.empty.all')
})

function selectFolder(id: string | null) {
  if (selectedFolderId.value === id) return
  selectedFolderId.value = id
  selectedIds.value = new Set()
  keyword.value = ''
  cancelRenameFolder()
}

function startRenameFolder() {
  if (!selectedFolder.value) return
  renameValue.value = selectedFolder.value.name || ''
  renamingFolder.value = true
  // 编辑态下屏蔽全局 Esc 快捷键（setting 页 Esc 默认会整页跳转回 /，见 plugins/keyListener.ts）
  useSysStore().togglePreventKeyEventsFlag(true)
  nextTick(() => renameInputRef.value?.focus())
}

function cancelRenameFolder() {
  if (!renamingFolder.value) return
  renamingFolder.value = false
  useSysStore().togglePreventKeyEventsFlag(false)
}

async function submitRenameFolder() {
  if (!renamingFolder.value) return
  renamingFolder.value = false
  useSysStore().togglePreventKeyEventsFlag(false)
  const folder = selectedFolder.value
  const name = renameValue.value.trim()
  if (!folder || !name || name === folder.name) return
  try {
    await bookmarksRenameDir(folder.id, name)
    bookmarkStore.nodes[folder.id] = { ...bookmarkStore.nodes[folder.id], name }
    toastStore.success(translate('bookmarkLibrary.folderPanel.renameSuccess'))
  } catch {
    // http 层已提示
  }
}

async function deleteFolder() {
  const folder = selectedFolder.value
  if (!folder) return
  try {
    await useConfirmStore().confirm(translate('bookmarkLibrary.folderPanel.deleteConfirmMessage', { name: folder.name || '' }), {
      title: translate('bookmarkLibrary.folderPanel.deleteConfirmTitle'),
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await bookmarksDel([folder.id])
    bookmarkStore.removeSubtree(folder.id)
    selectedFolderId.value = null
    selectedIds.value = new Set()
    toastStore.success(translate('bookmarkLibrary.folderPanel.deleteSuccess'))
  } catch {
    // http 层已提示
  }
}

function nodeKey(item: t.BookmarkShow): string | undefined {
  return item.layoutNodeId ?? undefined
}

function isSelected(item: t.BookmarkShow): boolean {
  const key = nodeKey(item)
  return !!key && selectedIds.value.has(key)
}

async function fetchPage() {
  loading.value = true
  try {
    const res = await bookmarksList({
      name: keyword.value.trim() || undefined,
      currentPage: currentPage.value,
      pageSize: pageSize.value,
      duplicatesOnly: filterMode.value === 'duplicate',
      invalidOnly: filterMode.value === 'invalid',
    })
    page.value = res
    // 清理已不在当前页的选中项，避免选中集合跨页无限增长
    const idsOnPage = new Set(res.records.map((r) => r.layoutNodeId).filter((id): id is string => !!id))
    selectedIds.value = new Set([...selectedIds.value].filter((id) => idsOnPage.has(id)))
  } catch {
    // http 层已提示
  } finally {
    loading.value = false
  }
}

const debouncedSearch = useDebounceFn(() => {
  // 文件夹视图下搜索是纯本地过滤（filteredFolderBookmarks），不需要也不应该触发后端分页请求
  if (selectedFolderId.value !== null) return
  currentPage.value = 1
  fetchPage()
}, 400)

watch(keyword, debouncedSearch)

watch(pageSize, () => {
  currentPage.value = 1
  fetchPage()
})

function setFilter(mode: FilterMode) {
  if (filterMode.value === mode) return
  filterMode.value = mode
  currentPage.value = 1
  selectedIds.value = new Set()
  fetchPage()
}

function goToPage(p: number) {
  if (p < 1 || p > totalPages.value) return
  currentPage.value = p
  fetchPage()
}

function toggleOne(item: t.BookmarkShow) {
  const key = nodeKey(item)
  if (!key) return
  const next = new Set(selectedIds.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  selectedIds.value = next
}

function toggleSelectAll() {
  if (allChecked.value) {
    selectedIds.value = new Set()
    return
  }
  selectedIds.value = new Set(displayItems.value.map((r) => nodeKey(r)).filter((id): id is string => !!id))
}

async function deleteOne(item: t.BookmarkShow) {
  const key = nodeKey(item)
  if (!key) return
  await performDelete([key])
}

async function batchDelete() {
  const ids = [...selectedIds.value]
  if (ids.length === 0) return
  await performDelete(ids)
}

// 静默删除：无需二次确认，前端立即本地清理去掉被删条目，避免等待网络的空窗期；
// 请求完成后（无论成功失败）都重新拉取当前页，用服务端结果（重复/失效标记、total 等联动状态）纠正本地状态。
// 文件夹视图下列表本就来自 bookmarkStore，removeNode 已经会同步摘除，不需要也不应该碰分页状态
async function performDelete(ids: string[]) {
  const removed = new Set(ids)
  const inAllView = selectedFolderId.value === null
  if (inAllView && page.value) {
    page.value = {
      ...page.value,
      records: page.value.records.filter((r) => !removed.has(nodeKey(r) ?? '')),
      total: Math.max(0, page.value.total - ids.length),
    }
  }
  selectedIds.value = new Set([...selectedIds.value].filter((id) => !removed.has(id)))

  try {
    await bookmarksDel(ids)
    // layoutNodeId 即桌面树节点 id：直接本地摘除，避免额外一次 update() 网络请求失败被吞掉导致首页残留
    for (const id of ids) bookmarkStore.removeNode(id)
  } catch {
    // http 层已提示
  } finally {
    if (inAllView) {
      // 若当前页因删除清空，且不是第一页，回退一页再拉取
      if (currentPage.value > 1 && page.value && page.value.records.length === 0) {
        currentPage.value -= 1
      }
      await fetchPage()
    }
  }
}

/* ========== 一键整理（清理失效 / 合并重复） ========== */

// 列表接口没有「取全部」的入口，只能按大页翻完；失效筛选后端没有显式 ORDER BY，
// 页数越少越不容易受分页间顺序漂移影响而漏项，故单页取大。漏掉的部分仍留在列表里，用户可再点一次。
const CLEANUP_PAGE_SIZE = 500
const CLEANUP_MAX_PAGES = 20
// 单次删除请求携带的节点数上限，避免一个超大 body 打过去
const CLEANUP_DELETE_CHUNK = 200

async function fetchAllByFilter(kind: CleanupKind): Promise<t.BookmarkShow[]> {
  const all: t.BookmarkShow[] = []
  for (let p = 1; p <= CLEANUP_MAX_PAGES; p++) {
    const res = await bookmarksList({
      currentPage: p,
      pageSize: CLEANUP_PAGE_SIZE,
      duplicatesOnly: kind === 'duplicate',
      invalidOnly: kind === 'invalid',
    })
    all.push(...res.records)
    if (res.records.length < CLEANUP_PAGE_SIZE || all.length >= res.total) break
  }
  return all
}

// 重复判定与后端一致：同一个 bookmarkId(同一站点链接)被本用户添加了多次。
// duplicatesOnly 下后端按 (bookmarkId, createTime) 升序返回，故每组第一条即最早添加的那条。
function planDuplicateCleanup(items: t.BookmarkShow[]): { groupCount: number; removeIds: string[] } {
  const groups = new Map<string, t.BookmarkShow[]>()
  for (const item of items) {
    if (!item.bookmarkId || !item.layoutNodeId) continue
    const list = groups.get(item.bookmarkId)
    if (list) list.push(item)
    else groups.set(item.bookmarkId, [item])
  }

  const removeIds: string[] = []
  let groupCount = 0
  for (const list of groups.values()) {
    if (list.length < 2) continue
    groupCount++
    // 置顶过的副本优先保留，否则保留最早添加的那条，避免把首页置顶区里的书签删掉
    const keep = list.find((i) => i.pinned) ?? list[0]
    for (const item of list) if (item !== keep) removeIds.push(item.layoutNodeId!)
  }
  return { groupCount, removeIds }
}

// 分批删除并同步摘除本地桌面树节点；无论成功失败都回到第一页重新拉取，用服务端结果纠正本地状态
async function runCleanup(kind: CleanupKind, ids: string[], successMessage: string) {
  cleanupBusy.value = kind
  try {
    for (let i = 0; i < ids.length; i += CLEANUP_DELETE_CHUNK) {
      const chunk = ids.slice(i, i + CLEANUP_DELETE_CHUNK)
      await bookmarksDel(chunk)
      for (const id of chunk) bookmarkStore.removeNode(id)
    }
    toastStore.success(successMessage)
  } catch {
    // http 层已提示
  } finally {
    cleanupBusy.value = null
    selectedIds.value = new Set()
    currentPage.value = 1
    await fetchPage()
  }
}

// 收集阶段也要占用 busy（翻页可能耗时），但确认框弹出前要先释放，避免弹窗期间按钮一直转圈
async function collectForCleanup(kind: CleanupKind): Promise<t.BookmarkShow[] | null> {
  cleanupBusy.value = kind
  try {
    return await fetchAllByFilter(kind)
  } catch {
    return null // http 层已提示
  } finally {
    cleanupBusy.value = null
  }
}

async function cleanInvalid() {
  if (cleanupBusy.value) return
  const items = await collectForCleanup('invalid')
  if (!items) return

  const ids = items
    .map((i) => i.layoutNodeId)
    .filter((id): id is string => !!id)
    // 尚未解析完的书签在后端同样是 isActivity=false，会被失效筛选带出来；
    // 用本地树里的节点类型把它们排除掉，避免刚导入还在排队解析的书签被误删
    .filter((id) => bookmarkStore.nodes[id]?.type !== HomeItemType.BOOKMARK_LOADING)
  if (ids.length === 0) {
    toastStore.info(translate('bookmarkLibrary.cleanup.invalidNone'))
    return
  }

  try {
    await useConfirmStore().confirm(translate('bookmarkLibrary.cleanup.invalidConfirmMessage', { count: ids.length }), {
      title: translate('bookmarkLibrary.cleanup.invalidConfirmTitle'),
      type: 'warning',
    })
  } catch {
    return
  }
  await runCleanup('invalid', ids, translate('bookmarkLibrary.cleanup.invalidSuccess', { count: ids.length }))
}

async function mergeDuplicates() {
  if (cleanupBusy.value) return
  const items = await collectForCleanup('duplicate')
  if (!items) return

  const { groupCount, removeIds } = planDuplicateCleanup(items)
  if (removeIds.length === 0) {
    toastStore.info(translate('bookmarkLibrary.cleanup.duplicateNone'))
    return
  }

  try {
    await useConfirmStore().confirm(
      translate('bookmarkLibrary.cleanup.duplicateConfirmMessage', { groups: groupCount, count: removeIds.length }),
      { title: translate('bookmarkLibrary.cleanup.duplicateConfirmTitle'), type: 'warning' },
    )
  } catch {
    return
  }
  await runCleanup('duplicate', removeIds, translate('bookmarkLibrary.cleanup.duplicateSuccess', { count: removeIds.length }))
}

function openCreateDialog() {
  if (selectedIds.value.size < 2) return
  newFolderName.value = ''
  createDialogRef.value?.showModal()
  nextTick(() => createNameInputRef.value?.focus())
}

function closeCreateDialog() {
  createDialogRef.value?.close()
}

async function confirmCreateCollection() {
  const name = newFolderName.value.trim()
  if (!name) return
  const ids = [...selectedIds.value]
  closeCreateDialog()
  try {
    await bookmarksCreateDir(ids, name, 0)
    toastStore.success(translate('bookmarkLibrary.createSuccess'))
    selectedIds.value = new Set()
    if (selectedFolderId.value === null) await fetchPage()
    bookmarkStore.update().catch(() => {})
  } catch {
    // http 层已提示
  }
}

onMounted(() => {
  fetchPage()
  if (!bookmarkStore.isFresh()) bookmarkStore.update().catch(() => {})
})
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
