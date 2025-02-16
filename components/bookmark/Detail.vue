<template>
  <div v-if="data.detail">
    <div class="text-[1.4rem] mb-[1rem]">书签信息</div>
    <div class="mb-[2rem]">{{ data.detail.urlFull }}</div>

    <img :src="data.detail.iconUrl" class="w-[5rem] h-[5rem] rounded-[1rem] shadow-lg mb-[2rem]">

    <div class="mb-[1rem]">
      <span class="cy-label-text">书签名称</span>
      <el-input @change="update" v-model="data.detail.title" maxlength="150" show-word-limit type="text" />
    </div>

    <div>
      <span class="cy-label-text">书签描述</span>
      <el-input @change="update" v-model="data.detail.description" maxlength="2000" show-word-limit type="textarea" />
    </div>

  </div>
</template>

<script lang="ts" setup>
import type {
  Bookmark,
  BookmarkUpdatePrams,
} from "~/server/apis/bookmark/typing";
import { bookmarksUpdate } from "~/server/apis";

const props = defineProps<{ data: Bookmark }>();

const data = reactive<{
  detail?: Bookmark;
}>({});

watchEffect(() => {
  data.detail = props.data;
});

function update() {
  if (data.detail) {
    const params: BookmarkUpdatePrams = {
      linkId: data.detail.bookmarkUserLinkId,
      title: data.detail.title,
      description: data.detail.description,
    };
    bookmarksUpdate(params);
  }
}
</script>

<style>
</style>