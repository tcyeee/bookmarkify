<template>
  <div class="flex flex-wrap flex-start gap-12">
    <!-- 二级菜单的返回 -->
    <!-- <BookmarkReturn v-show="data.subItemId" @click="backTopLayer()" /> -->

    <!-- 一级菜单 -->
    <draggable
      class="flex flex-wrap flex-start gap-12"
      v-show="!data.subItemId"
      v-model="pageData"
      v-bind="dragOptions"
      group="people"
      @sort="sort"
      item-key="id">
      <template #item="item">
        <div class="bookmark-item" @contextmenu="onContextMenu($event, item.element)">
          <LaunchpadCellBookmark
            v-if="item.element.type == 'BOOKMARK_DIR'"
            :value="item.element.typeDir"
            @click="openDir(item.element)" />
          <LaunchpadCellBookmark
            v-if="item.element.type == 'BOOKMARK'"
            :value="item.element.typeApp"
            @click="openPage(item.element.typeApp)" />
          <LaunchpadCellBookmarkLoading v-if="item.element.type == 'LOADING'" />
        </div>
      </template>
      <template #footer>
        <LaunchpadAddOne @success="addBookmark" />
      </template>
    </draggable>

    <!-- 二级菜单 -->
    <div v-if="data.subItemId" v-for="bookmark in data.subApps" :key="bookmark.bookmarkId">
      <LaunchpadCellBookmark :value="bookmark" @click="openPage(bookmark)" />
    </div>

    <!-- 书签详情 -->
    <el-dialog v-model="data.bookmarkDetailDialog" class="bookmark-dialog-box" :show-close="false" width="600" top="25vh">
      <LaunchpadDetail v-if="data.bookmarkDetail" :data="data.bookmarkDetail" />
    </el-dialog>
  </div>
</template>
<script lang="ts" setup>
import Draggable from 'vuedraggable'
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksSort, bookmarksDel } from '@api'
import type { HomeItem, Bookmark, BookmarkSortParams } from '@typing'

const sysStore = useSysStore()
const bookmarkStore = useBookmarkStore()

const pageData = computed<Array<HomeItem>>(() => bookmarkStore.homeItems || [])

const data = reactive<{
  subApps?: Array<Bookmark>
  subItemId?: string
  bookmarkDetailDialog: boolean
  bookmarkDetail?: Bookmark
}>({
  bookmarkDetailDialog: false,
})

watchEffect(() => {
  sysStore.preventKeyEventsFlag = data.bookmarkDetailDialog
})

const dragOptions = ref({ animation: 300, draggable: '.bookmark-item' })

function addBookmark(item: HomeItem) {
  if(item.typeApp!=null){
    bookmarkStore.homeItems.push(item);
  }else{
    bookmarkStore.addEmpty(item)
  }
}

function openDir(item: HomeItem) {
  data.subItemId = item.id
  data.subApps = item.typeDir.bookmarkList
  window.scrollTo({
    top: 0,
    behavior: 'smooth', // 平滑滚动到顶部，可选
  })
}

function openPage(bookmark: Bookmark) { 
  window.open(bookmark.urlFull, '_blank')
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

function onContextMenu(e: MouseEvent, item: HomeItem) {
  ContextMenu.showContextMenu({ items: getClickMenu(item), x: e.x, y: e.y })
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

// 重新排序
function sort() {
  let params: Array<BookmarkSortParams> = []
  pageData.value?.forEach((item) => {
    params.push({ id: item.id, sort: params.length })
  })
  bookmarksSort(params)
}
</script>

<style>
.bookmark-dialog-box {
  border-radius: 2rem;
  padding: 3rem;
}
</style>
