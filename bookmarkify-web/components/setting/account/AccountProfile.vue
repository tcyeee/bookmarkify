<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div class="space-y-6">
      <div
        class="rounded-2xl bg-linear-to-br from-slate-200 via-slate-300 to-gray-200 text-slate-800 p-6 sm:p-8 flex flex-col gap-6 sm:flex-row sm:items-center dark:from-slate-800 dark:via-slate-900 dark:to-slate-900 dark:text-slate-100">
        <div class="shrink-0">
          <AvatarUpload :avatar-path="avatarUrl" />
        </div>
        <div class="flex-1 space-y-3">
          <div class="flex flex-wrap items-center gap-3">
            <h2 class="text-2xl font-semibold leading-tight">
              {{ displayNickName }}
            </h2>
            <span class="cy-badge cy-badge-accent cy-badge-lg">{{ $t('accountProfile.verified') }}</span>
          </div>
          <div class="text-gray-500 text-xl uppercase font-jersey10">
            <span>UID: {{ maskedUid }}</span>
          </div>
        </div>
      </div>

      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-slate-800 dark:text-slate-100 py-3">{{ $t('accountProfile.basicInfo') }}</div>
        <ActionInput
          v-model="form.nickName"
          :label="$t('accountProfile.nickname')"
          :placeholder="$t('accountProfile.nicknamePlaceholder')"
          :max-length="20"
          :dirty="isDirty"
          :busy="saving"
          randomable
          :primary-text="$t('accountProfile.save')"
          :primary-loading-text="$t('accountProfile.saving')"
          :secondary-text="$t('accountProfile.cancel')"
          @primary="saveProfile"
          @secondary="resetForm"
          @random="randomizeNickName" />
      </div>

      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-slate-800 dark:text-slate-100">{{ $t('accountProfile.accountSecurity') }}</div>

        <div
          class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
          <div class="flex items-center gap-3 text-slate-700 dark:text-slate-200">
            <Icon icon="mdi:email" class="size-5 text-slate-500 dark:text-slate-400" />
            <div>
              <div class="font-medium">{{ $t('accountProfile.email') }}</div>
              <div class="text-sm text-slate-500 dark:text-slate-400">{{ maskedEmail || $t('accountProfile.emailUnbound') }}</div>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <BindEmailModal :email="form.email" :disabled="saving" @success="handleEmailBindSuccess" />
          </div>
        </div>

        <div
          class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
          <div class="flex items-center gap-3 text-slate-700 dark:text-slate-200">
            <svg class="size-5 shrink-0" viewBox="0 0 48 48" aria-hidden="true">
              <path
                fill="#FFC107"
                d="M43.611 20.083H42V20H24v8h11.303c-1.649 4.657-6.08 8-11.303 8c-6.627 0-12-5.373-12-12s5.373-12 12-12c3.059 0 5.842 1.154 7.961 3.039l5.657-5.657C34.046 6.053 29.268 4 24 4C12.955 4 4 12.955 4 24s8.955 20 20 20s20-8.955 20-20c0-1.341-.138-2.65-.389-3.917z" />
              <path
                fill="#FF3D00"
                d="m6.306 14.691l6.571 4.819C14.655 15.108 18.961 12 24 12c3.059 0 5.842 1.154 7.961 3.039l5.657-5.657C34.046 6.053 29.268 4 24 4C16.318 4 9.656 8.337 6.306 14.691z" />
              <path
                fill="#4CAF50"
                d="M24 44c5.166 0 9.86-1.977 13.409-5.192l-6.19-5.238A11.91 11.91 0 0 1 24 36c-5.202 0-9.619-3.317-11.283-7.946l-6.522 5.025C9.505 39.556 16.227 44 24 44z" />
              <path
                fill="#1976D2"
                d="M43.611 20.083H42V20H24v8h11.303a12.04 12.04 0 0 1-4.087 5.571l.003-.002l6.19 5.238C36.971 39.205 44 34 44 24c0-1.341-.138-2.65-.389-3.917z" />
            </svg>
            <div>
              <div class="font-medium">{{ $t('accountProfile.googleAccount') }}</div>
              <div class="text-sm text-slate-500 dark:text-slate-400">{{ googleEmail || $t('accountProfile.unlinked') }}</div>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <BindGoogleModal :google-email="googleEmail" :disabled="saving" />
          </div>
        </div>

        <div
          class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
          <div class="flex items-center gap-3 text-slate-700 dark:text-slate-200">
            <IconMdiGithub class="size-8 text-slate-800 dark:text-slate-100" />
            <div>
              <div class="font-medium">{{ $t('accountProfile.githubAccount') }}</div>
              <div class="text-sm text-slate-500 dark:text-slate-400">{{ githubLogin || $t('accountProfile.unlinked') }}</div>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <BindGithubModal :github-login="githubLogin" :disabled="saving" />
          </div>
        </div>
      </div>

      <!-- 帐户操作 -->
      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-slate-800 dark:text-slate-100">{{ $t('accountProfile.accountActions') }}</div>

        <!-- 退出登录 -->
        <div
          class="rounded-xl border border-slate-200 bg-white/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-slate-800 dark:bg-slate-900/70">
          <div class="flex items-center gap-3 text-slate-800 dark:text-slate-200">
            <Icon icon="mdi:logout" class="size-[22px] text-slate-500 dark:text-slate-400" />
            <div>
              <div class="font-semibold">{{ $t('accountProfile.logout') }}</div>
              <div class="text-sm text-slate-500 dark:text-slate-400">{{ $t('accountProfile.logoutDesc') }}</div>
            </div>
          </div>
          <AccountLogout />
        </div>
      </div>

      <!-- 危险操作 -->
      <div class="space-y-3 mt-20">
        <div class="text-lg font-semibold text-rose-600 dark:text-rose-300">{{ $t('accountProfile.dangerZone') }}</div>

        <!-- 注销账户 -->
        <div
          class="rounded-xl border border-rose-200 bg-rose-50/80 px-4 py-3 flex items-center justify-between gap-4 shadow-[0_1px_4px_rgba(0,0,0,0.04)] dark:border-rose-500/60 dark:bg-rose-500/10">
          <div class="flex items-center gap-3 text-rose-800 dark:text-rose-100">
            <Icon icon="mdi:logout" class="size-[22px] text-rose-500 dark:text-rose-300" />
            <div>
              <div class="font-semibold">{{ $t('accountProfile.deleteAccount') }}</div>
              <div class="text-sm text-rose-600 dark:text-rose-200">{{ $t('accountProfile.deleteAccountDesc') }}</div>
            </div>
          </div>
          <AccountDelete />
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, reactive, ref, watch } from 'vue'
import IconMdiGithub from '~icons/mdi/github'
import { updateUserInfo } from '@api'
import { randomNickName } from '@utils'
import type { UserInfo } from '@typing'
import AvatarUpload from './AvatarUpload.vue'
import { useAuthStore } from '@stores/auth.store'
import AccountDelete from './AccountDelete.vue'
import BindEmailModal from './BindEmailModal.vue'
import BindGoogleModal from './BindGoogleModal.vue'
import BindGithubModal from './BindGithubModal.vue'
import AccountLogout from './AccountLogout.vue'
import ActionInput from '../../common/ActionInput.vue'

const { t } = useI18n()
const authStore = useAuthStore()
const account = computed<UserInfo | undefined>(() => authStore.account)
// 直接读取 account.avatarUrl（持久化在 auth store），不再经 preferenceStore 中转；
// 这样即使 /user/info 偶发失败，已持久化的头像仍能显示，不会被静默清空。
const avatarUrl = computed(() => account.value?.avatarUrl ?? undefined)
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
const googleEmail = computed(() => account.value?.googleEmail ?? null)
const githubLogin = computed(() => account.value?.githubLogin ?? null)
const displayNickName = computed(() => account.value?.nickName || t('accountProfile.unnamedUser'))
const isDirty = computed(() => {
  const nicknameChanged = form.nickName !== (account.value?.nickName || '')
  const emailChanged = form.email !== (account.value?.email || '')
  return nicknameChanged || emailChanged
})
const form = reactive({
  nickName: '',
  email: '',
})
const saving = ref(false)

watch(
  account,
  (val) => {
    form.nickName = val?.nickName || ''
    form.email = val?.email || ''
  },
  { immediate: true },
)

async function saveProfile() {
  saving.value = true
  try {
    await updateUserInfo({
      nickName: form.nickName,
      email: form.email,
    })
    await authStore.refreshUserInfo()
    useToastStore().success(t('accountProfile.profileUpdateSuccess'))
  } catch {
    // 错误已由 http 层统一提示
  } finally {
    saving.value = false
  }
}

function resetForm() {
  form.nickName = account.value?.nickName || ''
  form.email = account.value?.email || ''
}

// 随机昵称：生成一个新昵称填入表单，与当前不同，触发 dirty 后由用户“保存”
function randomizeNickName() {
  let next = randomNickName()
  while (next === form.nickName) next = randomNickName()
  form.nickName = next
}

function handleEmailBindSuccess(email: string) {
  form.email = email
}
</script>

<style scoped>
:global(.fade-scale-enter-active),
:global(.fade-scale-leave-active) {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

:global(.fade-scale-enter-from),
:global(.fade-scale-leave-to) {
  opacity: 0;
  transform: translateX(10px);
}
</style>
