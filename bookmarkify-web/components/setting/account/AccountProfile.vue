<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <!-- 已验证账号 -->
    <div v-if="isAuthed" class="space-y-6">
      <div
        class="rounded-2xl bg-linear-to-br from-slate-200 via-slate-300 to-gray-200 text-slate-800 p-6 sm:p-8 flex flex-col gap-6 sm:flex-row sm:items-center dark:from-slate-800 dark:via-slate-900 dark:to-slate-900 dark:text-slate-100">
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
        <div class="text-lg font-semibold text-slate-800 dark:text-slate-100 py-3">基本信息</div>
        <ActionInput
          v-model="form.nickName"
          label="昵称"
          placeholder="请输入昵称"
          :max-length="20"
          :dirty="isDirty"
          :busy="saving"
          primary-text="保存"
          primary-loading-text="保存中..."
          secondary-text="取消"
          @primary="saveProfile"
          @secondary="resetForm" />
      </div>

      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-slate-800 dark:text-slate-100">账号安全</div>

        <div
          class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
          <div class="flex items-center gap-3 text-slate-700 dark:text-slate-200">
            <span class="icon--memory-email icon-size-20 text-slate-500 dark:text-slate-400"></span>
            <div>
              <div class="font-medium">邮箱</div>
              <div class="text-sm text-slate-500 dark:text-slate-400">{{ maskedEmail || '未绑定邮箱' }}</div>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <BindEmailModal :email="form.email" :disabled="saving" @success="handleEmailBindSuccess" />
          </div>
        </div>

        <div
          class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
          <div class="flex items-center gap-3 text-slate-700 dark:text-slate-200">
            <span class="icon--memory-speaker icon-size-20 text-slate-500 dark:text-slate-400"></span>
            <div>
              <div class="font-medium">手机号</div>
              <div class="text-sm text-slate-500 dark:text-slate-400">{{ maskedPhone || '未绑定手机号' }}</div>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <BindPhoneModal :phone="form.phone" :disabled="saving" @success="handlePhoneBindSuccess" />
          </div>
        </div>
      </div>

      <!-- 帐户操作 -->
      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-slate-800 dark:text-slate-100">账号操作</div>

        <!-- 退出登录 -->
        <div
          class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
          <div class="flex items-center gap-3 text-slate-800 dark:text-slate-200">
            <span class="icon--memory-arrow-down-right-box icon-size-22 text-slate-500 dark:text-slate-400"></span>
            <div>
              <div class="font-semibold">退出登录</div>
              <div class="text-sm text-slate-500 dark:text-slate-400">仅退出当前设备登录，不影响账号数据。</div>
            </div>
          </div>
          <AccountLogout />
        </div>

        <!-- 注销账户 -->
        <div
          v-show="false"
          class="rounded-xl border border-rose-200 bg-rose-50/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-rose-500/60 dark:bg-rose-500/10">
          <div class="flex items-center gap-3 text-rose-800 dark:text-rose-100">
            <span class="icon--memory-arrow-down-right-box icon-size-22 text-rose-500 dark:text-rose-300"></span>
            <div>
              <div class="font-semibold">注销账户</div>
              <div class="text-sm text-rose-600 dark:text-rose-200">注销后账号及数据将被销毁且不可恢复，请谨慎操作。</div>
            </div>
          </div>
          <AccountDelete />
        </div>
      </div>
    </div>

    <!-- 临时/未验证账号 -->
    <div v-else class="text-center">
      <LoginDialog />
    </div>

    <!-- 退出登录 -->
    <div
      class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
      <div class="flex items-center gap-3 text-slate-800 dark:text-slate-200">
        <span class="icon--memory-arrow-down-right-box icon-size-22 text-slate-500 dark:text-slate-400"></span>
        <div>
          <div class="font-semibold">退出登录</div>
          <div class="text-sm text-slate-500 dark:text-slate-400">仅退出当前设备登录，不影响账号数据。</div>
        </div>
      </div>
      <AccountLogout />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, reactive, ref, watch } from 'vue'
import { updateUserInfo } from '@api'
import type { UserInfo } from '@typing'
import AvatarUpload from './AvatarUpload.vue'
import { useAuthStore } from '@stores/auth.store'
import { AuthStatusEnum } from '@typing'
import AccountDelete from './AccountDelete.vue'
import BindPhoneModal from './BindPhoneModal.vue'
import BindEmailModal from './BindEmailModal.vue'
import AccountLogout from './AccountLogout.vue'
import LoginDialog from './LoginDialog.vue'
import ActionInput from '../../common/ActionInput.vue'

const authStore = useAuthStore()
const accountStatus = computed<AuthStatusEnum | undefined>(() => authStore.authStatus)
const account = computed<UserInfo | undefined>(() => authStore.account)
const avatarUrl: Ref<string | undefined> = computed(() => authStore.account?.avatar?.currentName)
const isAuthed = computed(() => accountStatus.value === AuthStatusEnum.AUTHED)
const maskedUid = computed(() => {
  const uid = account.value?.uid
  if (!uid) return '——'
  if (uid.length <= 8) return uid
  return `${uid.slice(0, 8)}••••${uid.slice(-8)}`
})
const maskedEmail = computed(() => {
  const email = form.email
  if (!email) return ''
  const parts = email.split('@')
  if (parts.length !== 2) return email
  const [local, domain] = parts
  if (!local) return email
  if (local.length <= 2) return `${local[0]}*@${domain}`
  const start = local[0]
  const end = local[local.length - 1]
  const middle = '*'.repeat(local.length - 2)
  return `${start}${middle}${end}@${domain}`
})
const maskedPhone = computed(() => {
  const phone = form.phone
  if (!phone) return ''
  if (phone.length <= 4) return phone
  const start = phone.slice(0, 3)
  const end = phone.slice(-4)
  const middle = '*'.repeat(Math.max(phone.length - 7, 0))
  return `${start}${middle}${end}`
})
const displayNickName = computed(() => account.value?.nickName || '未命名用户')
const isDirty = computed(() => {
  const nicknameChanged = form.nickName !== (account.value?.nickName || '')
  const phoneChanged = form.phone !== (account.value?.phone || '')
  const emailChanged = form.email !== (account.value?.email || '')
  return nicknameChanged || phoneChanged || emailChanged
})
const form = reactive({
  nickName: '',
  phone: '',
  email: '',
})
const saving = ref(false)

watch(
  account,
  (val) => {
    form.nickName = val?.nickName || ''
    form.phone = val?.phone || ''
    form.email = val?.email || ''
  },
  { immediate: true }
)

async function saveProfile() {
  saving.value = true
  try {
    await updateUserInfo({
      nickName: form.nickName,
      phone: form.phone,
      email: form.email,
    })
    await authStore.refreshUserInfo()
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
  form.email = account.value?.email || ''
}

async function handleAvatarUpdate(avatarPath: string) {
  // 头像上传成功后，更新用户信息中的头像路径
  await authStore.refreshUserInfo()
}

function handlePhoneBindSuccess(phone: string) {
  form.phone = phone
}

function handleEmailBindSuccess(email: string) {
  form.email = email
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
