<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div>
      <h3 class="text-xl font-semibold">{{ $t('bookmarkLibrary.title') }}</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ $t('bookmarkLibrary.desc') }}</p>
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
      <div class="flex items-center gap-2">
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

    <!-- 列表 -->
    <div class="rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden">
      <div
        class="flex items-center gap-3 px-4 py-2.5 bg-slate-50 dark:bg-slate-800/60 border-b border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">
        <input type="checkbox" class="cy-checkbox cy-checkbox-sm" :checked="allChecked" :disabled="records.length === 0" @change="toggleSelectAll" />
        <span class="flex-1">{{ $t('bookmarkLibrary.columns.bookmark') }}</span>
        <span class="w-32 shrink-0 text-center hidden sm:block">{{ $t('bookmarkLibrary.columns.folder') }}</span>
        <span class="w-16 shrink-0 text-center">{{ $t('bookmarkLibrary.columns.status') }}</span>
        <span class="w-8 shrink-0" />
      </div>

      <div v-if="loading" class="py-16 text-center text-sm text-slate-400 dark:text-slate-500">
        <Icon icon="mdi:loading" class="size-6 animate-spin mx-auto mb-2" />
        {{ $t('bookmarkLibrary.loading') }}
      </div>
      <div v-else-if="records.length === 0" class="py-16 text-center text-sm text-slate-400 dark:text-slate-500">
        {{ emptyText }}
      </div>
      <div v-else class="max-h-[28rem] overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
        <label
          v-for="item in records"
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
          <span class="w-32 shrink-0 text-center text-xs truncate text-slate-500 dark:text-slate-400 hidden sm:block">{{ item.folderName || '—' }}</span>
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

    <!-- 分页 -->
    <div class="flex items-center justify-between text-sm text-slate-500 dark:text-slate-400">
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
import { bookmarksList, bookmarksDel, bookmarksCreateDir } from '@api'
import type * as t from '@typing'
import { useDebounceFn } from '@vueuse/core'

type FilterMode = 'all' | 'duplicate' | 'invalid'

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

const createDialogRef = ref<HTMLDialogElement | null>(null)
const createNameInputRef = ref<HTMLInputElement | null>(null)
const newFolderName = ref('')

const filters = computed(() => [
  { value: 'all' as FilterMode, label: translate('bookmarkLibrary.filters.all') },
  { value: 'duplicate' as FilterMode, label: translate('bookmarkLibrary.filters.duplicate') },
  { value: 'invalid' as FilterMode, label: translate('bookmarkLibrary.filters.invalid') },
])

const records = computed(() => page.value?.records ?? [])
const totalPages = computed(() => (page.value ? Math.max(1, Math.ceil(page.value.total / pageSize.value)) : 1))
const allChecked = computed(() => records.value.length > 0 && records.value.every((r) => isSelected(r)))

const emptyText = computed(() => {
  if (filterMode.value === 'duplicate') return translate('bookmarkLibrary.empty.duplicate')
  if (filterMode.value === 'invalid') return translate('bookmarkLibrary.empty.invalid')
  return translate('bookmarkLibrary.empty.all')
})

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
  selectedIds.value = new Set(records.value.map((r) => nodeKey(r)).filter((id): id is string => !!id))
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
// 请求完成后（无论成功失败）都重新拉取当前页，用服务端结果（重复/失效标记、total 等联动状态）纠正本地状态
async function performDelete(ids: string[]) {
  const removed = new Set(ids)
  if (page.value) {
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
    // 若当前页因删除清空，且不是第一页，回退一页再拉取
    if (currentPage.value > 1 && page.value && page.value.records.length === 0) {
      currentPage.value -= 1
    }
    await fetchPage()
  }
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
    await fetchPage()
    bookmarkStore.update().catch(() => {})
  } catch {
    // http 层已提示
  }
}

onMounted(fetchPage)
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
