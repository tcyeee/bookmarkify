<template>
  <Transition name="fade-main">
    <BookmarkList v-if="status" :data="data.bookmarkList" class="mx-[5rem] sm:w-[90vw] md:w-[80vw] lg:w-[70vw] xl:w-[70rem]" />
  </Transition>
</template>

<script lang="ts" setup>
import { type HomeItem } from "~/server/apis/bookmark/typing";
const storeBookmark = StoreBookmark();

defineProps<{
  status: boolean;
}>();

const data = reactive<{
  bookmarkList: Array<HomeItem>;
}>({
  bookmarkList: storeBookmark.bookmarks || [],
});

onMounted(() => {
  storeBookmark.addAction(updateAllBookmark);
});

function updateAllBookmark() {
  storeBookmark.get().then((res) => (data.bookmarkList = res));
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