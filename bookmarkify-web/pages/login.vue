<template>
  <div class="login-mobile-bg relative min-h-dvh w-full overflow-hidden flex flex-col text-white">
    <!-- 背景光晕，与 welcome.vue 的 hero 区一致 -->
    <div class="absolute inset-0 pointer-events-none">
      <div class="halo halo-1" />
      <div class="halo halo-2" />
      <div class="halo halo-3" />
    </div>

    <button
      type="button"
      aria-label="返回"
      class="absolute top-4 left-4 z-20 flex h-9 w-9 items-center justify-center rounded-full text-white/50 transition-all duration-200 hover:bg-white/10 hover:text-white/80"
      @click="navigateTo('/welcome')">
      <Icon icon="mdi:arrow-left" class="size-5" />
    </button>

    <div class="relative z-10 flex flex-1 flex-col justify-center px-5 py-12">
      <div class="mx-auto w-full max-w-[400px]">
        <div v-if="!isVerifying" class="flex flex-col items-center pb-6">
          <div
            class="mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-br from-slate-700 via-slate-900 to-black shadow-lg shadow-black/30">
            <Icon icon="mdi:bookmark" class="size-6 text-white" />
          </div>
          <h1 class="text-xl font-semibold text-white">欢迎登录 Bookmarkify</h1>
          <p class="mt-1.5 text-sm text-white/45">登录后可保障数据安全并支持跨设备同步</p>
        </div>

        <EmailLoginPanel v-if="selectedMethod === 'email'" key="email" @success="onSuccess" @step="verifyStep = $event" />
        <PasswordLoginPanel v-else key="password" @success="onSuccess" />

        <div v-if="!isVerifying" class="mt-5 text-center text-sm">
          <button type="button" class="text-white/40 transition-colors hover:text-white/70" @click="toggleMethod">
            {{ selectedMethod === 'email' ? '使用密码登录' : '使用邮箱验证码登录' }}
          </button>
        </div>

        <div v-if="!isVerifying" class="mt-6">
          <div class="flex items-center gap-3 text-xs text-white/20">
            <span class="h-px flex-1 bg-white/8"></span>
            <span>或</span>
            <span class="h-px flex-1 bg-white/8"></span>
          </div>
          <div class="mt-4 flex flex-col items-center gap-3">
            <GoogleLoginButton />
            <!-- 弹窗登录在移动端浏览器/微信内置浏览器中不可靠，改用整页跳转 -->
            <GithubLoginButton redirect />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import EmailLoginPanel from '../components/welcome/login/EmailLoginPanel.vue'
import PasswordLoginPanel from '../components/welcome/login/PasswordLoginPanel.vue'
import GoogleLoginButton from '../components/welcome/login/GoogleLoginButton.vue'
import GithubLoginButton from '../components/welcome/login/GithubLoginButton.vue'
import { useAuthStore } from '@stores/auth.store'

definePageMeta({ middleware: 'auth', layout: 'default' })

const authStore = useAuthStore()
const selectedMethod = ref<'email' | 'password'>('email')

// 验证码步骤：进入验证码输入后隐藏头部、切换方式与第三方登录，逻辑与 WelcomeLoginDialog 一致
const verifyStep = ref(1)
const isVerifying = computed(() => verifyStep.value === 2)
watch(selectedMethod, () => (verifyStep.value = 1))

function toggleMethod() {
  selectedMethod.value = selectedMethod.value === 'email' ? 'password' : 'email'
}

async function onSuccess() {
  await authStore.postLoginSetup()
  await navigateTo('/')
}
</script>

<style scoped>
/* 与 pages/welcome.vue 的 .welcome-hero / .halo 保持一致的深色玻璃拟态背景 */
.login-mobile-bg {
  background:
    radial-gradient(circle at 20% 20%, rgba(255, 255, 255, 0.12), transparent 40%),
    radial-gradient(circle at 80% 30%, rgba(148, 163, 184, 0.16), transparent 45%),
    radial-gradient(circle at 50% 80%, rgba(15, 23, 42, 0.32), transparent 50%), #0b1220;
}

.halo {
  position: absolute;
  width: 62vmax;
  height: 62vmax;
  border-radius: 9999px;
  filter: blur(120px);
  opacity: 0.65;
  animation: haloDrift 26s ease-in-out infinite alternate;
  mix-blend-mode: screen;
}

.halo-1 {
  background: radial-gradient(circle, rgba(255, 255, 255, 0.24), transparent 55%);
  top: -28%;
  left: -20%;
}

.halo-2 {
  background: radial-gradient(circle, rgba(148, 163, 184, 0.28), transparent 52%);
  top: 10%;
  right: -25%;
  animation-duration: 32s;
  animation-delay: 4s;
}

.halo-3 {
  background: radial-gradient(circle, rgba(71, 85, 105, 0.28), transparent 60%);
  bottom: -35%;
  left: 20%;
  animation-duration: 38s;
  animation-delay: 2s;
}

@keyframes haloDrift {
  0% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  50% {
    transform: translate3d(4%, -3%, 0) scale(1.08);
  }
  100% {
    transform: translate3d(-3%, 2%, 0) scale(0.98);
  }
}
</style>
