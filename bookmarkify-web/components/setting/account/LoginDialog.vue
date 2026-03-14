<template>
  <!-- 品牌头部 -->
  <div class="flex flex-col items-center pb-7 pt-1">
    <div
      class="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-sky-400 via-indigo-500 to-fuchsia-500 shadow-lg shadow-indigo-500/30">
      <span class="icon--memory-bookmark icon-size-24 text-white"></span>
    </div>
    <h2 class="text-xl font-semibold text-white">欢迎登录 Bookmarkify</h2>
    <p class="mt-1.5 text-sm text-white/45">选择登录方式，保障账号安全与跨设备同步</p>
  </div>

  <!-- 登录方式 Tab -->
  <div class="mb-5 flex gap-1 rounded-xl bg-white/5 p-1">
    <button
      v-for="m in methods"
      :key="m.key"
      type="button"
      @click="selectedMethod = m.key"
      :class="[
        'flex flex-1 items-center justify-center gap-1.5 rounded-lg py-2 text-sm font-medium transition-all duration-200',
        selectedMethod === m.key
          ? 'bg-white/12 text-white shadow-sm ring-1 ring-white/10'
          : 'text-white/40 hover:text-white/65',
      ]">
      <span :class="[m.icon, 'icon-size-16']"></span>
      {{ m.label }}
    </button>
  </div>

  <!-- 当前方式说明 -->
  <div class="mb-5 flex items-start gap-3 rounded-xl border border-white/6 bg-white/4 px-4 py-3">
    <span :class="[currentMethod.icon, 'icon-size-20 mt-0.5 shrink-0 text-indigo-300']"></span>
    <p class="text-sm leading-relaxed text-white/55">{{ currentMethod.desc }}</p>
  </div>

  <!-- 登录按钮 -->
  <button
    type="button"
    @click="startLogin"
    :disabled="saving"
    class="relative w-full overflow-hidden rounded-xl py-3 text-sm font-semibold text-white shadow-lg shadow-indigo-500/25 transition-all duration-200 hover:scale-[1.01] hover:shadow-indigo-500/40 active:scale-[0.99] disabled:cursor-wait disabled:opacity-60">
    <span class="absolute inset-0 bg-gradient-to-r from-sky-500 via-indigo-500 to-fuchsia-500"></span>
    <span class="absolute inset-0 bg-gradient-to-r from-sky-400 via-indigo-400 to-fuchsia-400 opacity-0 transition-opacity duration-200 hover:opacity-100"></span>
    <span class="relative flex items-center justify-center gap-2">
      <span v-if="saving" class="icon--memory-rotate-clockwise icon-size-16 animate-spin"></span>
      {{ saving ? '处理中...' : '开始登录' }}
    </span>
  </button>

  <!-- 第三方登录 -->
  <div class="mt-7">
    <div class="flex items-center gap-3 text-xs text-white/20">
      <span class="h-px flex-1 bg-white/8"></span>
      <span>第三方登录</span>
      <span class="h-px flex-1 bg-white/8"></span>
    </div>
    <div class="mt-4 flex justify-center gap-3">
      <button
        v-for="s in socials"
        :key="s.label"
        type="button"
        :aria-label="s.label"
        :title="s.label"
        @click="showThirdPartyNotReady(s.label)"
        class="flex h-10 w-10 items-center justify-center rounded-full border border-white/10 bg-white/5 text-white/40 shadow-sm transition-all duration-200 hover:border-indigo-400/50 hover:bg-white/10 hover:text-white/80 hover:scale-105">
        <span :class="[s.icon, 'icon-size-20']"></span>
      </button>
    </div>
  </div>

  <!-- 复用绑定组件作为登录流程 -->
  <BindPhoneModal ref="phoneModalRef" :phone="form.phone" :disabled="saving" hideTrigger @success="handlePhoneSuccess" />
  <BindEmailModal ref="emailModalRef" :email="form.email" :disabled="saving" hideTrigger @success="handleEmailSuccess" />
  <LoginPasswordModal ref="passwordModalRef" :disabled="saving" hideTrigger @success="handlePasswordSuccess" />
</template>

<script lang="ts" setup>
import { nextTick, ref, watch } from 'vue'
import type { UserInfo, LoginMethod } from '@typing'
import { useAuthStore } from '@stores/auth.store'
import BindPhoneModal from './BindPhoneModal.vue'
import BindEmailModal from './BindEmailModal.vue'
import LoginPasswordModal from './LoginPasswordModal.vue'

const authStore = useAuthStore()
const selectedMethod = ref<LoginMethod['key']>('phone')
const account = computed<UserInfo | undefined>(() => authStore.account)
const phoneModalRef = ref<InstanceType<typeof BindPhoneModal>>()
const emailModalRef = ref<InstanceType<typeof BindEmailModal>>()
const passwordModalRef = ref<InstanceType<typeof LoginPasswordModal>>()

const form = reactive({
  nickName: '',
  phone: '',
  email: '',
})
const saving = ref(false)

const methods = [
  {
    key: 'phone' as LoginMethod['key'],
    label: '手机号',
    icon: 'icon--memory-speaker',
    desc: '通过短信验证码快速验证身份，安全便捷，无需记忆密码。',
  },
  {
    key: 'email' as LoginMethod['key'],
    label: '邮箱',
    icon: 'icon--memory-email',
    desc: '使用邮箱接收验证码完成登录，适合常用邮箱的用户。',
  },
  {
    key: 'password' as LoginMethod['key'],
    label: '密码',
    icon: 'icon--memory-lock',
    desc: '使用手机号或邮箱配合已设置的密码直接登录。',
  },
]

const socials = [
  { label: '微信', icon: 'icon--icon-wechat' },
  { label: 'Github', icon: 'icon--icon-github' },
  { label: '谷歌', icon: 'icon--icon-google' },
  { label: '抖音', icon: 'icon--icon-tiktok' },
]

const currentMethod = computed(() => methods.find((m) => m.key === selectedMethod.value) ?? methods[0])

async function startLogin() {
  saving.value = true
  try {
    await nextTick()
    if (selectedMethod.value === 'phone') {
      phoneModalRef.value?.openDialog()
    } else if (selectedMethod.value === 'password') {
      passwordModalRef.value?.openDialog()
    } else {
      emailModalRef.value?.openDialog()
    }
  } finally {
    saving.value = false
  }
}

function handlePhoneSuccess(phone: string) {
  form.phone = phone
  selectedMethod.value = 'phone'
  ElNotification.success({ message: '手机号登录成功（模拟）' })
}

function handleEmailSuccess(email: string) {
  form.email = email
  selectedMethod.value = 'email'
  ElNotification.success({ message: '邮箱登录成功（模拟）' })
}

function handlePasswordSuccess(account: string) {
  selectedMethod.value = 'password'
  ElNotification.success({ message: `账号 ${account} 登录成功` })
}

watch(
  account,
  (val) => {
    form.nickName = val?.nickName || ''
    form.phone = val?.phone || ''
    form.email = val?.email || ''
  },
  { immediate: true },
)

function showThirdPartyNotReady(provider: string) {
  ElMessage.info(`暂未适配 ${provider} 登录`)
}
</script>
