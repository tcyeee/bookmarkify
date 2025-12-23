<template>
  <!-- 已绑定手机号或者邮箱 -->
  <div v-if="accountStatus === AuthStatusEnum.AUTHED">
    <!-- 可以绑定其他登录方式,比如手机号,微信公众号,邮箱,支付宝,微信支付等 -->
  </div>

  <!-- 使用的临时设备ID登录的 -->
  <div v-else>
    <!-- 提示用户未登陆,要求至少提供一种登录方式,比如手机号,微信公众号,邮箱,支付宝,微信支付等 -->
  </div>

  <div class="setting-title">基础信息</div>
  <AvatarUpload :avatar-path="avatarUrl" @update="handleAvatarUpdate" />
  <div class="setting-subtitle">昵称</div>
  <div class="cy-tooltip" data-tip="先绑定手机号或者邮箱才能修改昵称哦~">
    <input :disabled="!account.verified" type="text" placeholder="请输入昵称" v-model="account.nickName" class="cy-input" />
  </div>
  <button v-if="account.nickName != account.nickName" @click="update()" class="cy-btn cy-btn-soft ml-2">修改</button>
</template>

<script lang="ts" setup>
import { updateUserInfo } from '@api'
import type { UserInfo } from '@typing'
import AvatarUpload from './AvatarUpload.vue'
import { useUserStore } from '@stores/user.store'
import { AuthStatusEnum } from '@typing'
const userStore = useUserStore()

const accountStatus = computed<AuthStatusEnum | undefined>(() => userStore.authStatus)
const account = computed<UserInfo | undefined>(() => userStore.account)
const avatarUrl: Ref<string | undefined> = computed(() => userStore.account?.avatar?.currentName)

function update() {
  const params = {
    // nickName: props.info.nickName,
    // phone: props.info.phone,
  }
  updateUserInfo(params).then((res: any) => {
    if (!res) return
    ElNotification.success({ message: '个人资料修改成功' })
  })
}

async function handleAvatarUpdate(avatarPath: string) {
  // 头像上传成功后，更新用户信息中的头像路径
  await userStore.refreshUserInfo()
}
</script>
