<template>
  <Transition name="fade-main">
    <BookmarkList v-if="status" :data="data.bookmarkList" class="mx-[5rem] sm:w-[90vw] md:w-[80vw] lg:w-[70vw] xl:w-[70rem]" />
  </Transition>
</template>

<script lang="ts" setup>
import { bookmarksShowAll } from "~/server/apis";
import { type HomeItem } from "~/server/apis/bookmark/typing";
const userStore = StoreUser();

defineProps<{
  status: boolean;
}>();

const data = reactive<{
  bookmarkList: Array<HomeItem>;
}>({
  bookmarkList: [],
});

onMounted(() => {});

function getPageData() {
  bookmarksShowAll().then((res) => sortData(res));
}

function sortData(res: Array<HomeItem>) {
  console.log(`加载用户书签,共计${res.length}个...`);
  data.bookmarkList =
    res == null || res.length == 0
      ? []
      : res.slice().sort((a, b) => a.sort - b.sort);
}
</script>

<style scoped>
/*
  进入和离开动画可以使用不同
  持续时间和速度曲线。
*/
.fade-main-enter-active,
.fade-main-leave-active {
  transition: all 100ms ease-in-out;
}

.fade-main-enter-from,
.fade-main-leave-to {
  transform: scale(1.2);
  opacity: 0;
}
</style>