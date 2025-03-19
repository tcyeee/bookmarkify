<template>
  <div>
    <!-- 个人资料 -->
    <div class="setting-title">基础信息</div>
    <div class="cy-avatar my-10">
      <div class="ring-primary ring-offset-base-100 w-24 rounded-full ring ring-offset-2">
        <img src="~/assets/avatar/default.png" />
      </div>
    </div>

    <div class="setting-subtitle">昵称</div>
    <input :disabled="data.userInfo.nickName == undefined" type="text" placeholder="请输入昵称" v-model="data.userInfo.nickName" class="cy-input" />
    <button v-if="data.userInfo.nickName != data.userInfoRaw?.nickName" @click="update()" class="cy-btn cy-btn-soft ml-2">修改</button>

    <!-- 账户绑定 -->
    <div class="setting-title mt-25">账户绑定</div>
    <div class="setting-subtitle">手机号</div>
    <label class="cy-input cy-validator">
      <span class="icon--phone" />
      <input type="tel" class="tabular-nums" required placeholder="phone" pattern="[0-9]*" minlength="10" maxlength="10" title="Must be 10 digits" />
    </label>
    <p class="cy-validator-hint">Must be 10 digits</p>

    <div class="setting-subtitle">邮箱</div>
    <label class="cy-input cy-validator">
      <span class="icon--mail" />
      <input type="email" placeholder="mail@site.com" required />
    </label>
    <div class="cy-validator-hint hidden">Enter valid email address</div>

    <!-- 注销账户 -->
    <div class="setting-title mt-25">注销账户</div>
    <div class="setting-subtitle">注销后账号所有数据将被销毁并不可找回，请谨慎操作。</div>
    <label class="cy-btn cy-btn-error rounded-2xl mt-2" @click="toggleDelDialog(true)">注销账户</label>

    <!-- 注销账户 -->
    <input type="checkbox" id="userDelDialog" class="cy-modal-toggle" />
    <div class="cy-modal" role="dialog">
      <div class="cy-modal-box">
        <div class="flex items-end gap-2">
          <span class="icon--warning"></span>
          <h3 class="text-lg font-bold text-red-500">警告: 账户注销后将无法找回!</h3>
        </div>
        <p class="mt-5 setting-subtitle">注销前需要验证密码:</p>
        <input v-model="data.password" type="password" placeholder="请输入密码" class="cy-input " />

        <div class="cy-modal-action">
          <button @click="toggleDelDialog(false)" class="cy-btn">取消</button>
          <button :disabled="data.password.length==0" class="cy-btn cy-btn-error" @click="accountDel()">确认注销!</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import {
  queryUserInfo,
  updateUserInfo,
  accountDelete,
} from "~/server/apis/user";
import type { UserInfoShow } from "~/server/apis/user/typing";
const sysStore = useSysStore();

var data = reactive({
  userInfoRaw: {} as UserInfoShow,
  userInfo: {} as UserInfoShow,
  password: "",
});

onMounted(() => {
  getUserInfo();
});

function accountDel() {
  accountDelete(data.password);
}

function toggleDelDialog(status: boolean) {
  document.getElementById("userDelDialog")?.click();
  sysStore.preventKeyEventsFlag = status;
}

function update() {
  const params = {
    nickName: data.userInfo.nickName,
    phone: data.userInfo.phone,
  };
  updateUserInfo(params).then((res) => {
    if (!res) return;
    data.userInfoRaw = data.userInfo;
    ElNotification.success({ message: "个人资料修改成功" });
  });
}

function getUserInfo() {
  queryUserInfo().then((res) => {
    data.userInfo = structuredClone(res);
    data.userInfoRaw = structuredClone(res);
  });
}
</script>

<style lang="scss" scoped>
</style>