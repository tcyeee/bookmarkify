<template>
  <div>
    <!-- 注销账户 -->
    <div class="setting-title mt-25">注销账户</div>
    <div class="setting-subtitle">
      注销后账号所有数据将被销毁并不可找回，请谨慎操作。
    </div>
    <button class="cy-btn cy-btn-error rounded-2xl mt-2" @click="openDelDialog">
      注销账户
    </button>

    <!-- 注销账户对话框 -->
    <dialog id="userDelDialog" class="cy-modal">
      <div class="cy-modal-box">
        <div class="flex items-end gap-2">
          <span class="icon--warning"></span>
          <h3 class="text-lg font-bold text-red-500">
            警告: 账户注销后将无法找回!
          </h3>
        </div>
        <p class="mt-5 setting-subtitle">注销前需要验证密码:</p>
        <input
          v-model="data.password"
          type="password"
          placeholder="请输入密码"
          class="cy-input"
        />

        <div class="cy-modal-action">
          <button @click="closeDelDialog" class="cy-btn">取消</button>
          <button
            :disabled="data.password.length == 0"
            class="cy-btn cy-btn-error"
            @click="accountDel()"
          >
            确认注销!
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button @click="closeDelDialog">close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { accountDelete } from "@api";
const sysStore = useSysStore();

var data = reactive({
  password: "",
});

function accountDel() {
  accountDelete(data.password);
}

function openDelDialog() {
  if (!import.meta.client) return;
  const dialog = document.getElementById("userDelDialog") as HTMLDialogElement;
  if (!dialog) return;
  dialog.showModal();
  sysStore.preventKeyEventsFlag = true;
}

function closeDelDialog() {
  if (!import.meta.client) return;
  const dialog = document.getElementById("userDelDialog") as HTMLDialogElement;
  if (!dialog) return;
  dialog.close();
  sysStore.preventKeyEventsFlag = false;
  data.password = "";
}
</script>

<style></style>
