<template>
  <div>
    <div class="bookmark-icon" @click="sysStore.addBookmarkDialogVisible=true">
      <span class="icon--add" />
    </div>

    <dialog id="dialog_add" class="cy-modal">
      <div class="cy-modal-box min-h-100">

        <!-- Button for create bookmark -->
        <div class="fixed bottom-8 left-8 right-8">
          <transition name="el-fade-in">
            <div v-if="data.notice" class="cy-chat cy-chat-start mb-4">
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
        <button @click="sysStore.addBookmarkDialogVisible=false">close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { bookmarksAddOne } from "@api";
import type { HomeItem } from "@api/typing";

const sysStore = useSysStore();

// 监测到添加书签窗口的显示与否
watchEffect(() => {
  toggleAddDialog(sysStore.addBookmarkDialogVisible);
});

// 根据标志位显示或关闭添加书签对话框
function toggleAddDialog(flag: boolean) {
  if (!import.meta.client) return;
  const element = document.getElementById("dialog_add") as HTMLDialogElement;
  if (!element) return;
  if (flag) {
    element.showModal();
  } else {
    element.close();
  }
}

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
      sysStore.addBookmarkDialogVisible = false;
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