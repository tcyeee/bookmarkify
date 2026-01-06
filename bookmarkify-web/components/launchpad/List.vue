<template>
  <div class="bookmark-grid-wrapper" :style="gridStyle">
    <!-- 一级菜单 -->
    <ClientOnly>
      <Vuuri
        :key="bookmarkLayoutKey"
        v-show="!data.subItemId"
        class="bookmark-grid"
        :model-value="gridItems"
        item-key="id"
        :options="vuuriOptions"
        drag-handle=".bookmark-drag-handle"
        :drag-enabled="true"
        :get-item-width="getItemWidth"
        :get-item-height="getItemHeight"
        @input="onGridInput"
      @dragStart="onDragStart"
        @dragReleaseEnd="onDragReleaseEnd">
        <template #item="{ item }">
          <div
            class="bookmark-item"
            :class="{ 'is-add': isAddItem(item) }"
            @contextmenu="onItemContextMenu($event, item)">
            <div v-if="isAddItem(item)" class="bookmark-add-placeholder">
              <LaunchpadAddOne @success="addBookmark" />
            </div>
            <div v-else class="bookmark-drag-handle">
              <LaunchpadCellBookmark
                v-if="item.type == 'BOOKMARK_DIR'"
                :value="item.typeDir"
                :show-title="showTitle"
                @click="openDir(item)" />
              <LaunchpadCellBookmark
                v-else-if="item.type == 'BOOKMARK'"
                :value="item.typeApp"
                :show-title="showTitle"
                @click="openPage(item.typeApp)" />
              <LaunchpadCellBookmarkLoading v-else-if="item.type == 'LOADING'" :show-title="showTitle" />
            </div>
          </div>
        </template>
      </Vuuri>
    </ClientOnly>

    <!-- 二级菜单 -->
    <div v-if="data.subItemId" class="bookmark-sub-grid">
      <div v-for="bookmark in data.subApps" :key="bookmark.bookmarkId" class="bookmark-item">
        <LaunchpadCellBookmark :value="bookmark" :show-title="showTitle" @click="openPage(bookmark)" />
      </div>
    </div>

    <!-- 书签详情 -->
    <el-dialog v-model="data.bookmarkDetailDialog" class="bookmark-dialog-box" :show-close="false" width="600" top="25vh">
      <LaunchpadDetail v-if="data.bookmarkDetail" :data="data.bookmarkDetail" />
    </el-dialog>
  </div>
</template>
<script lang="ts" setup>
import { defineAsyncComponent, defineComponent } from 'vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksSort, bookmarksDel } from '@api'
import { BookmarkLayoutMode, BookmarkOpenMode, type HomeItem, type Bookmark, type BookmarkSortParams } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'

const sysStore = useSysStore()
const bookmarkStore = useBookmarkStore()
const preferenceStore = usePreferenceStore()

const Vuuri = process.client
  ? defineAsyncComponent(() => import('vuuri'))
  : defineComponent({
      name: 'VuuriPlaceholder',
      setup: () => () => null,
    })

const pageData = computed<Array<HomeItem>>(() => bookmarkStore.homeItems || [])
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
const bookmarkOpenMode = computed<BookmarkOpenMode>(
  () => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB,
)

type AddItem = { id: string; sort: number; type: 'ADD' }
type GridItem = HomeItem | AddItem

const ADD_ITEM_ID = '__bookmark_add_placeholder__'

const layoutConfig = computed(() => {
  const layout = preferenceStore.preference?.bookmarkLayout ?? BookmarkLayoutMode.DEFAULT
  switch (layout) {
    case BookmarkLayoutMode.COMPACT:
      return { gap: 1.5, size: 4.25 }
    case BookmarkLayoutMode.SPACIOUS:
      return { gap: 4, size: 6 }
    case BookmarkLayoutMode.DEFAULT:
    default:
      return { gap: 3, size: 5 }
  }
})

const bookmarkLayoutKey = computed(() => preferenceStore.preference?.bookmarkLayout ?? BookmarkLayoutMode.DEFAULT)
const gridGap = computed<number>(() => layoutConfig.value.gap)
const bookmarkGapRem = computed<string>(() => `${gridGap.value}rem`)
const bookmarkSize = computed<string>(() => `${layoutConfig.value.size}rem`)
const bookmarkCellWidth = computed<string>(() => `calc(${bookmarkSize.value} + ${bookmarkGapRem.value})`)

const gridStyle = computed(() => ({
  '--bookmark-gap': `${gridGap.value}rem`,
  '--bookmark-size': bookmarkSize.value,
}))

const addButtonItem = computed<AddItem>(() => ({
  id: ADD_ITEM_ID,
  sort: Number.MAX_SAFE_INTEGER,
  type: 'ADD',
}))

const gridItems = computed<GridItem[]>(() => [...(pageData.value ?? []), addButtonItem.value])
const vuuriOptions = {
  layout: { fillGaps: true, rounding: false },
  layoutDuration: 250,
  showDuration: 0,
  hideDuration: 0,
  dragReleaseDuration: 50,
}

const data = reactive<{
  subApps?: Array<Bookmark>
  subItemId?: string
  bookmarkDetailDialog: boolean
  bookmarkDetail?: Bookmark
}>({
  bookmarkDetailDialog: false,
})

const dragState = reactive({
  dragging: false,
  justDropped: false,
})

watchEffect(() => {
  sysStore.preventKeyEventsFlag = data.bookmarkDetailDialog
})

function addBookmark(item: HomeItem) {
  if (item.typeApp != null) {
    bookmarkStore.homeItems.push(item)
  } else {
    bookmarkStore.addEmpty(item)
  }
}

function openDir(item: HomeItem) {
  if (dragState.dragging || dragState.justDropped) return
  data.subItemId = item.id
  data.subApps = item.typeDir.bookmarkList
  window.scrollTo({
    top: 0,
    behavior: 'smooth', // 平滑滚动到顶部，可选
  })
}

function openPage(bookmark: Bookmark) {
  if (dragState.dragging || dragState.justDropped) return
  const target = bookmarkOpenMode.value === BookmarkOpenMode.NEW_TAB ? '_blank' : '_self'
  window.open(bookmark.urlFull, target)
}

function getClickMenu(item: HomeItem) {
  return [
    {
      label: '查看详情',
      onClick: () => clickDetail(item),
    },
    {
      label: '删除书签',
      onClick: () => delOne(item),
    },
  ]
}

// 查看详情
function clickDetail(item: HomeItem) {
  data.bookmarkDetailDialog = true
  data.bookmarkDetail = item.typeApp
}

// 删除书签
function delOne(item: HomeItem) {
  bookmarksDel([item.id])

  const index: number = pageData.value?.findIndex((a) => a.id == item.id) || -1
  if (index !== -1) pageData.value?.splice(index, 1)
}

function isAddItem(item: GridItem): item is AddItem {
  return item.id === ADD_ITEM_ID
}

function onItemContextMenu(e: MouseEvent, item: GridItem) {
  if (isAddItem(item)) return
  ContextMenu.showContextMenu({ items: getClickMenu(item), x: e.x, y: e.y })
}

function getItemWidth() {
  return bookmarkCellWidth.value
}

function getItemHeight(item: GridItem) {
  const titleSpace = showTitle.value ? '1.6rem' : '0rem'
  return isAddItem(item)
    ? bookmarkCellWidth.value
    : `calc(${bookmarkSize.value} + ${titleSpace} + ${bookmarkGapRem.value})`
}

function onGridInput(items: GridItem[]) {
  bookmarkStore.homeItems = items.filter((it): it is HomeItem => !isAddItem(it))
}

function onDragStart() {
  dragState.dragging = true
  dragState.justDropped = false
}

function onDragReleaseEnd() {
  dragState.dragging = false
  dragState.justDropped = true
  if (typeof requestAnimationFrame !== 'undefined') {
    requestAnimationFrame(() => {
      dragState.justDropped = false
    })
  } else {
    setTimeout(() => (dragState.justDropped = false), 16)
  }
  sort()
}

// 重新排序
function sort() {
  const params: Array<BookmarkSortParams> = []
  bookmarkStore.homeItems?.forEach((item) => {
    params.push({ id: item.id, sort: params.length })
  })
  bookmarksSort(params)
}
</script>

<style>
.bookmark-grid-wrapper {
  width: 100%;
  --bookmark-gap: 3rem;
  --bookmark-size: 5rem;
}

.bookmark-grid {
  width: 100%;
  min-height: calc(var(--bookmark-size) + 1.6rem + var(--bookmark-gap));
}

.bookmark-grid .muuri-item {
  margin: 0;
}

.bookmark-grid .bookmark-item {
  width: var(--bookmark-size);
}

.bookmark-drag-handle {
  cursor: grab;
}

.bookmark-add-placeholder {
  width: var(--bookmark-size);
  height: var(--bookmark-size);
  display: flex;
  align-items: center;
  justify-content: center;
}

.bookmark-sub-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--bookmark-gap);
}

.bookmark-sub-grid .bookmark-item {
  width: var(--bookmark-size);
}

.bookmark-dialog-box {
  border-radius: 2rem;
  padding: 3rem;
}
</style>
