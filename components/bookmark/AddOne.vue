<template>
  <div onclick="dialog_add.showModal()">

    <div class="bookmark-icon">
      <span class="icon--add" />
    </div>

    <dialog id="dialog_add" class="cy-modal">
      <div class="cy-modal-box min-h-[25rem]">

        <!-- Button for create bookmark -->
        <div class="fixed bottom-[2rem] left-[2rem] right-[2rem]">
          <transition name="el-fade-in">
            <div v-if="data.notice" class="cy-chat cy-chat-start mb-[1rem]">
              <div class="cy-chat-bubble">{{ data.notice }}</div>
            </div>
          </transition>

          <label class="cy-input flex items-center gap-2 w-full" :class="data.urlIsTrue?'cy-input-success':''">
            <span class="icon--earth" />
            <div>http://</div>
            <input v-model="data.input" type="text" placeholder="输入网址.." @input="checkInput" @keyup.enter="addOne" />
            <kbd class="cy-kbd cursor-pointer" @click="addOne">&emsp;enter&emsp; </kbd>
          </label>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button>close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksAddOne } from "~/server/apis";
import type { HomeItem } from "~/server/apis/bookmark/typing";

const emit = defineEmits(["success"]);
const data = reactive<{
  status: boolean;
  input?: string;
  urlIsTrue: boolean;
  notice?: string;
}>({
  urlIsTrue: false,
  status: false,
});

function addOne() {
  if (!data.input) return;
  if (!isUrl(data.input)) {
    data.notice = "你输入的网址看起来有点怪...";
  }

  bookmarksAddOne({ url: data.input }).then((res: HomeItem) => {
    emit("success", res);
    data.notice = "添加成功!";
    data.input = undefined;
    setTimeout(() => {
      data.notice = undefined;
      // @ts-ignore
      document.getElementById("dialog_add")?.close();
    }, 500);
  });
}

function checkInput() {
  if (!data.input) return;
  data.urlIsTrue = isUrl(data.input);
  if (data.urlIsTrue) data.notice = undefined;
}

function isUrl(url: string): boolean {
  var pattern = RegExp(
    "^(https?:\\/\\/)?" + // 协议部分，可选，http或https开头
      "((([a-z\\d]([a-z\\d-]*[a-z\\d])*)\\.)+[a-z]{2,}|" + // 域名部分
      "((\\d{1,3}\\.){3}\\d{1,3}))" + // 或者IP地址部分
      "(\\:\\d+)?(\\/[-a-z\\d%_.~+]*)*" + // 端口号和路径部分
      "(\\?[;&a-z\\d%_.~+=-]*)?" + // 查询字符串
      "(\\#[-a-z\\d_]*)?$",
    "i"
  );
  return pattern.test(url);
}
</script>

<style lang="scss" scoped>
</style>