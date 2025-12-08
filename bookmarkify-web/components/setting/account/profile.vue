<template>
  <div v-if="account">
    <div v-if="!account.verified" role="alert" class="cy-alert cy-alert-warning mb-5">
      <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 shrink-0 stroke-current" fill="none" viewBox="0 0 24 24">
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
      </svg>
      <span>危险: 您还未绑定手机号或者邮箱，当前使用临时帐户，请尽快绑定</span>
    </div>

    <div class="setting-title">基础信息</div>

    <AvatarUpload :avatar-path="avatarUrl" @update="handleAvatarUpdate" />

    <div class="setting-subtitle">昵称</div>
    <div class="cy-tooltip" data-tip="先绑定手机号或者邮箱才能修改昵称哦~">
      <input :disabled="!account.verified" type="text" placeholder="请输入昵称" v-model="account.nickName" class="cy-input" />
    </div>
    <button v-if="account.nickName != account.nickName" @click="update()" class="cy-btn cy-btn-soft ml-2">修改</button>
  </div>
</template>

<script lang="ts" setup>
import { updateUserInfo } from '@api'
import type { UserInfoEntity } from '@typing'
import AvatarUpload from './AvatarUpload.vue'
import { useUserStore } from '@stores/user.store'
const userStore = useUserStore()

const account = computed<UserInfoEntity | undefined>(() => userStore.account)
const avatarUrl: Ref<string | undefined> = computed(() => userStore.avatarUrl)

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
  await userStore.getUserInfo()
}
</script>
