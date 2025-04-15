<template>
  <div>
    <div class="setting-title">基础信息</div>
    <div class="cy-avatar my-10">
      <div class="ring-primary ring-offset-base-100 w-24 rounded-full ring ring-offset-2">
        <img src="~/assets/avatar/default.png" />
      </div>
    </div>

    <div class="setting-subtitle">昵称</div>
    <input :disabled="props.info.nickName == undefined" type="text" placeholder="请输入昵称" v-model="props.info.nickName" class="cy-input" />
    <button v-if="props.info.nickName != props.info.nickName" @click="update()" class="cy-btn cy-btn-soft ml-2">修改</button>

  </div>
</template>

<script lang="ts" setup>
import { updateUserInfo } from "@api";
import type { UserInfoShow } from "@api/typing";

const props = defineProps<{
  info: UserInfoShow;
}>();

onMounted(() => {
  console.log(props.info);
});

function update() {
  const params = {
    nickName: props.info.nickName,
    phone: props.info.phone,
  };
  updateUserInfo(params).then((res: any) => {
    if (!res) return;
    ElNotification.success({ message: "个人资料修改成功" });
  });
}
</script>

<style>
</style>