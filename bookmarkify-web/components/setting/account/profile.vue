<template>
  <div class="space-y-6">
    <!-- 已验证账号 -->
    <div v-if="!isAuthed" class="space-y-6">
      <div
        class="rounded-2xl bg-linear-to-br from-slate-200 via-slate-300 to-gray-200 text-slate-800 p-6 sm:p-8 flex flex-col gap-6 sm:flex-row sm:items-center">
        <div class="shrink-0">
          <AvatarUpload :avatar-path="avatarUrl" @update="handleAvatarUpdate" />
        </div>
        <div class="flex-1 space-y-3">
          <div class="flex flex-wrap items-center gap-3">
            <h2 class="text-2xl font-semibold leading-tight">
              {{ displayNickName }}
            </h2>
            <span class="cy-badge cy-badge-accent cy-badge-lg">已验证</span>
          </div>
          <div class="text-gray-500 text-xl uppercase font-jersey10">
            <span>UID: {{ maskedUid }}</span>
          </div>
        </div>
      </div>

      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-slate-800 py-3">基本信息</div>
        <div class="flex items-end gap-3">
          <label class="block flex-1 min-w-0">
            <span class="text-sm text-slate-600">昵称</span>
            <input
              v-model="form.nickName"
              type="text"
              maxlength="20"
              placeholder="请输入昵称"
              class="mt-1 h-12 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200" />
          </label>
          <div
            class="overflow-hidden transition-[width] duration-200 ease-out flex justify-end gap-2"
            :style="buttonWrapperStyle">
            <button
              class="cy-btn cy-btn-accent h-12 px-5 min-w-[96px] transition duration-180 ease-out"
              :style="buttonStyle"
              @click="saveProfile"
              :disabled="saving">
              <span v-if="saving">保存中...</span>
              <span v-else>保存</span>
            </button>
            <button
              class="cy-btn cy-btn-ghost h-12 px-4 min-w-[80px] transition duration-180 ease-out"
              :style="buttonStyle"
              @click="resetForm"
              :disabled="saving">
              取消
            </button>
          </div>
        </div>
      </div>

      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-slate-800">账号安全</div>
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2 text-slate-600">
            <span class="icon--memory-email icon-size-20"></span>
            <div>邮箱</div>
          </div>
          <span class="cy-badge" :class="account?.email ? 'cy-badge-accent' : 'cy-badge-ghost'">
            {{ account?.email ? '已绑定' : '未绑定' }}
          </span>
        </div>
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2 text-slate-600">
            <span class="icon--memory-speaker icon-size-20"></span>
            <div>手机号</div>
          </div>
          <span class="cy-badge" :class="account?.phone ? 'cy-badge-accent' : 'cy-badge-ghost'">
            {{ account?.phone ? '已绑定' : '未绑定' }}
          </span>
        </div>
      </div>

      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-slate-800">账号操作</div>
        <!-- 退出账号 -->
        <div
          class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)]">
          <div class="flex items-start gap-3 text-slate-800">
            <span class="icon--memory-arrow-down-right-box icon-size-22 text-slate-500"></span>
            <div>
              <div class="font-semibold">退出登录</div>
              <div class="text-sm text-slate-500">仅退出当前设备登录，不影响账号数据。</div>
            </div>
          </div>
          <button class="cy-btn cy-btn-ghost rounded-2xl" @click="userStore.logout()">退出账号</button>
        </div>

        <!-- 注销账户 -->
        <div
          class="rounded-xl border border-rose-200 bg-rose-50/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)]">
          <div class="flex items-start gap-3 text-rose-800">
            <span class="icon--memory-arrow-down-right-box icon-size-22 text-rose-500"></span>
            <div>
              <div class="font-semibold">注销账户</div>
              <div class="text-sm text-rose-600">注销后账号及数据将被销毁且不可恢复，请谨慎操作。</div>
            </div>
          </div>
          <CancelAccount />
        </div>
      </div>
    </div>

    <!-- 临时/未验证账号 -->
    <div v-else class="rounded-2xl border border-dashed border-slate-300 bg-white/70 p-8 text-center shadow-sm">
      <div class="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-slate-100 text-slate-500">
        <span class="icon--memory-account-alert icon-size-26"></span>
      </div>
      <div class="text-xl font-semibold text-slate-800 mb-2">未完成验证</div>
      <div class="text-slate-600 mb-4">请绑定手机号或邮箱完成验证，以便保障账号安全并支持跨设备同步。</div>
      <div class="flex justify-center gap-3">
        <button class="cy-btn cy-btn-accent" @click="triggerLogin" :disabled="saving">立即登录/注册</button>
        <button class="cy-btn cy-btn-ghost" @click="userStore.refreshUserInfo()" :disabled="saving">刷新状态</button>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, reactive, ref, watch } from 'vue'
import { updateUserInfo } from '@api'
import type { UserInfo } from '@typing'
import AvatarUpload from './AvatarUpload.vue'
import { useUserStore } from '@stores/user.store'
import { AuthStatusEnum } from '@typing'
import CancelAccount from './cancel.vue'

const userStore = useUserStore()

const accountStatus = computed<AuthStatusEnum | undefined>(() => userStore.authStatus)
const account = computed<UserInfo | undefined>(() => userStore.account)
const avatarUrl: Ref<string | undefined> = computed(() => userStore.account?.avatar?.currentName)
const isAuthed = computed(() => accountStatus.value === AuthStatusEnum.AUTHED)
const maskedUid = computed(() => {
  const uid = account.value?.uid
  if (!uid) return '——'
  if (uid.length <= 8) return uid
  return `${uid.slice(0, 8)}••••${uid.slice(-8)}`
})
const displayNickName = computed(() => account.value?.nickName || '未命名用户')
const isDirty = computed(() => form.nickName !== (account.value?.nickName || ''))
const buttonWrapperStyle = computed(() => ({
  width: isDirty.value ? '190px' : '0px',
}))

const buttonStyle = computed(() => ({
  opacity: isDirty.value ? 1 : 0,
  transform: isDirty.value ? 'translateX(0)' : 'translateX(10px)',
  pointerEvents: isDirty.value ? 'auto' : 'none',
}))

const form = reactive({
  nickName: '',
  phone: '',
})
const saving = ref(false)

watch(
  account,
  (val) => {
    form.nickName = val?.nickName || ''
    form.phone = val?.phone || ''
  },
  { immediate: true }
)

async function saveProfile() {
  saving.value = true
  try {
    await updateUserInfo({
      nickName: form.nickName,
      phone: form.phone,
    })
    await userStore.refreshUserInfo()
    ElNotification.success({ message: '个人资料修改成功' })
  } catch (error: any) {
    ElMessage.error(error?.message || '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

function resetForm() {
  form.nickName = account.value?.nickName || ''
  form.phone = account.value?.phone || ''
}

async function handleAvatarUpdate(avatarPath: string) {
  // 头像上传成功后，更新用户信息中的头像路径
  await userStore.refreshUserInfo()
}

async function triggerLogin() {
  saving.value = true
  try {
    await userStore.loginOrRegister()
  } catch (error: any) {
    ElMessage.error(error?.message || '登录失败，请稍后重试')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
:global(.fade-scale-enter-active),
:global(.fade-scale-leave-active) {
  transition: opacity 180ms ease, transform 180ms ease;
}

:global(.fade-scale-enter-from),
:global(.fade-scale-leave-to) {
  opacity: 0;
  transform: translateX(10px);
}
</style>
