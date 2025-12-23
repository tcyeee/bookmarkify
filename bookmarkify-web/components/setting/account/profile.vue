<template>
  <div class="space-y-6">
    <!-- 已验证账号 -->
    <div v-if="!isAuthed" class="space-y-6">
      <div
        class="rounded-2xl bg-linear-to-r from-slate-900 via-slate-800 to-slate-700 text-white p-6 sm:p-8 flex flex-col gap-6 sm:flex-row sm:items-center shadow-md">
        <div class="shrink-0">
          <AvatarUpload :avatar-path="avatarUrl" @update="handleAvatarUpdate" />
        </div>
        <div class="flex-1 space-y-3">
          <div class="flex flex-wrap items-center gap-3">
            <h2 class="text-2xl font-semibold leading-tight">
              {{ form.nickName || account?.nickName || '未命名用户' }}
            </h2>
            <span class="cy-badge cy-badge-accent cy-badge-lg">已验证</span>
          </div>
          <div class="text-slate-200/80 text-sm flex flex-wrap gap-3">
            <span>UID: {{ account?.uid || '——' }}</span>
            <span class="inline-flex items-center gap-1">
              <span class="icon--memory-email icon-size-18"></span>
              {{ account?.email || '未绑定邮箱' }}
            </span>
            <span class="inline-flex items-center gap-1">
              <span class="icon--memory-call icon-size-18"></span>
              {{ account?.phone || '未绑定手机号' }}
            </span>
          </div>
        </div>
      </div>

      <div class="grid gap-4 md:grid-cols-2">
        <div class="rounded-xl border border-slate-100 bg-white/80 p-5 shadow-sm">
          <div class="flex items-center justify-between mb-4">
            <div>
              <div class="text-lg font-semibold text-slate-800">基本信息</div>
              <div class="text-sm text-slate-500">修改昵称或手机号</div>
            </div>
            <span class="cy-badge cy-badge-ghost">资料</span>
          </div>
          <div class="space-y-4">
            <label class="block">
              <span class="text-sm text-slate-600">昵称</span>
              <input
                v-model="form.nickName"
                type="text"
                maxlength="20"
                placeholder="请输入昵称"
                class="mt-1 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200" />
            </label>
            <label class="block">
              <span class="text-sm text-slate-600">手机号</span>
              <input
                v-model="form.phone"
                type="tel"
                inputmode="tel"
                placeholder="可选，便于找回账号"
                class="mt-1 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200" />
            </label>
            <div class="flex justify-end gap-2">
              <button class="cy-btn cy-btn-ghost" @click="resetForm" :disabled="saving">重置</button>
              <button class="cy-btn cy-btn-accent" @click="saveProfile" :disabled="saving">
                <span v-if="saving">保存中...</span>
                <span v-else>保存</span>
              </button>
            </div>
          </div>
        </div>

        <div class="rounded-xl border border-slate-100 bg-white/80 p-5 shadow-sm flex flex-col gap-4">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-lg font-semibold text-slate-800">账号安全</div>
              <div class="text-sm text-slate-500">邮箱 / 手机绑定状态</div>
            </div>
            <span class="cy-badge cy-badge-success">安全</span>
          </div>
          <div class="space-y-3">
            <div class="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-3">
              <div class="flex items-center gap-2 text-slate-700">
                <span class="icon--memory-email icon-size-20 text-slate-500"></span>
                <div>
                  <div class="font-medium">邮箱</div>
                  <div class="text-sm text-slate-500">{{ account?.email || '未绑定' }}</div>
                </div>
              </div>
              <span class="cy-badge" :class="account?.email ? 'cy-badge-accent' : 'cy-badge-ghost'">
                {{ account?.email ? '已绑定' : '未绑定' }}
              </span>
            </div>
            <div class="flex items-center justify-between rounded-lg border border-slate-100 px-3 py-3">
              <div class="flex items-center gap-2 text-slate-700">
                <span class="icon--memory-call icon-size-20 text-slate-500"></span>
                <div>
                  <div class="font-medium">手机号</div>
                  <div class="text-sm text-slate-500">{{ account?.phone || '未绑定' }}</div>
                </div>
              </div>
              <span class="cy-badge" :class="account?.phone ? 'cy-badge-accent' : 'cy-badge-ghost'">
                {{ account?.phone ? '已绑定' : '未绑定' }}
              </span>
            </div>
            <div class="text-sm text-slate-500">提示：当前登录状态已验证，可在上方补充手机号或邮箱，便于找回账号。</div>
          </div>
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

const userStore = useUserStore()

const accountStatus = computed<AuthStatusEnum | undefined>(() => userStore.authStatus)
const account = computed<UserInfo | undefined>(() => userStore.account)
const avatarUrl: Ref<string | undefined> = computed(() => userStore.account?.avatar?.currentName)
const isAuthed = computed(() => accountStatus.value === AuthStatusEnum.AUTHED)

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
