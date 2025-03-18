<template>
  <div class="flex flex-wrap flex-start gap-[3rem]">
    <!-- 二级菜单的返回 -->
    <BookmarkReturn v-show="data.subItemId" @click="backTopLayer()" />

    <!-- 一级菜单 -->
    <draggable class="flex flex-wrap flex-start gap-[3rem]" v-show="!data.subItemId" v-model="data.pageData" v-bind="dragOptions" group="people" @sort="sort" item-key="id">
      <template #item="item">
        <div @contextmenu="onContextMenu($event,item.element)">
          <BookmarkCellDir v-if="item.element.type == 'BOOKMARK_DIR'" :value="item.element.typeDir" @click="openDir(item.element)" />
          <BookmarkCellItem v-if="item.element.type == 'BOOKMARK'" :value="item.element.typeApp" @click="openPage(item.element.typeApp)" />
          <BookmarkCellLoading v-if="item.element.type == 'LOADING'" />
        </div>
      </template>
    </draggable>

    <!-- 二级菜单 -->
    <div v-if="data.subItemId" v-for="bookmark in data.subApps" :key="bookmark.bookmarkId">
      <BookmarkCellItem :value="bookmark" @click="openPage(bookmark)" />
    </div>

    <!-- 一级菜单的添加 -->
    <BookmarkAddOne v-show="!data.subItemId" @success="addOne" />

    <!-- 书签详情 -->
    <el-dialog v-model="data.bookmarkDetailDialog" class="bookmark-dialog-box" :show-close="false" width="600" top="25vh">
      <BookmarkDetail v-if="data.bookmarkDetail" :data="data.bookmarkDetail" />
    </el-dialog>
  </div>
</template>
<script lang="ts" setup>
import Draggable from "vuedraggable";
import ContextMenu from "@imengyu/vue3-context-menu";
import { bookmarksSort, bookmarksDel } from "~/server/apis";
import type {
  HomeItem,
  Bookmark,
  BookmarkSortParams,
} from "~/server/apis/bookmark/typing";

const sysStore = useSysStore();
const bookmarkStore = useBookmarkStore();

const props = defineProps<{
  data: Array<HomeItem>;
}>();

const data = reactive<{
  subApps?: Array<Bookmark>;
  subItemId?: string;
  pageData?: Array<HomeItem>;
  bookmarkDetailDialog: boolean;
  bookmarkDetail?: Bookmark;
}>({
  pageData: props.data,
  bookmarkDetailDialog: false,
});

watchEffect(() => {
  data.pageData = props.data;
  sysStore.preventKeyEventsFlag = data.bookmarkDetailDialog;
});

const dragOptions = ref({ animation: 300 });

function addOne(item: HomeItem) {
  bookmarkStore.addEmpty(item);
}

function openDir(item: HomeItem) {
  data.subItemId = item.id;
  data.subApps = item.typeDir.bookmarkList;
  window.scrollTo({
    top: 0,
    behavior: "smooth", // 平滑滚动到顶部，可选
  });
}

function openPage(bookmark: Bookmark) {
  window.open(bookmark.urlFull, "_blank");
}

function backTopLayer() {
  data.subItemId = undefined;
  window.scrollTo({
    top: 0,
    behavior: "smooth", // 平滑滚动到顶部，可选
  });
}

function getClickMenu(item: HomeItem) {
  return [
    {
      label: "查看详情",
      onClick: () => clickDetail(item),
    },
    {
      label: "删除书签",
      onClick: () => delOne(item),
    },
  ];
}

function onContextMenu(e: MouseEvent, item: HomeItem) {
  ContextMenu.showContextMenu({ items: getClickMenu(item), x: e.x, y: e.y });
}

// 查看详情
function clickDetail(item: HomeItem) {
  data.bookmarkDetailDialog = true;
  data.bookmarkDetail = item.typeApp;
}

// 删除书签
function delOne(item: HomeItem) {
  bookmarksDel([item.id]);

  const index: number = data.pageData?.findIndex((a) => a.id == item.id) || -1;
  if (index !== -1) data.pageData?.splice(index, 1);
}

// 重新排序
function sort() {
  let params: Array<BookmarkSortParams> = [];
  data.pageData?.forEach((item) => {
    params.push({ id: item.id, sort: params.length });
  });
  bookmarksSort(params);
}
</script>

<style >
.bookmark-dialog-box {
  border-radius: 2rem;
  padding: 3rem;
}
</style>