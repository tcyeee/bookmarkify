<template>
  <!-- 未配置 ClientId 时不渲染，避免出现一个点不动的按钮 -->
  <div v-if="clientId" class="flex flex-col items-center gap-2">
    <!-- Google 官方按钮挂载点（GIS 会把 iframe 按钮渲染进来） -->
    <div ref="btnRef" class="min-h-[40px]" />
    <p v-if="errorMsg" class="text-center text-xs text-red-400">{{ errorMsg }}</p>
  </div>
</template>

<script lang="ts" setup>
import { useAuthStore } from '@stores/auth.store'

const emit = defineEmits<{ (e: 'success'): void }>()

const authStore = useAuthStore()
const config = useRuntimeConfig()
const clientId = (config.public.googleClientId as string | undefined) || ''

const btnRef = ref<HTMLElement>()
const errorMsg = ref('')

const GSI_SRC = 'https://accounts.google.com/gsi/client'

// 动态加载 GIS 脚本（全局只加载一次）
function loadGsiScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if ((window as any).google?.accounts?.id) return resolve()
    const exist = document.querySelector(`script[src="${GSI_SRC}"]`) as HTMLScriptElement | null
    if (exist) {
      exist.addEventListener('load', () => resolve())
      exist.addEventListener('error', () => reject(new Error('GIS 脚本加载失败')))
      return
    }
    const script = document.createElement('script')
    script.src = GSI_SRC
    script.async = true
    script.defer = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('GIS 脚本加载失败'))
    document.head.appendChild(script)
  })
}

// 收到 Google 回调（凭据为 ID Token JWT）后走后端校验登录
async function handleCredential(response: { credential?: string }) {
  if (!response?.credential) {
    errorMsg.value = '未获取到 Google 凭据'
    return
  }
  errorMsg.value = ''
  try {
    await authStore.loginWithGoogle(response.credential)
    emit('success')
  } catch (err: any) {
    // http 客户端已对 1xx 业务码统一弹窗，这里仅做兜底提示
    errorMsg.value = err?.msg || '谷歌登录失败，请重试'
  }
}

onMounted(async () => {
  if (!clientId) return
  try {
    await loadGsiScript()
    const google = (window as any).google
    google.accounts.id.initialize({
      client_id: clientId,
      callback: handleCredential,
    })
    // 渲染 Google 官方按钮：深色填充 + 胶囊形，宽度贴合弹窗内容区
    const width = Math.min(Math.max(btnRef.value?.clientWidth || 320, 200), 400)
    google.accounts.id.renderButton(btnRef.value, {
      type: 'standard',
      theme: 'filled_black',
      size: 'large',
      shape: 'pill',
      text: 'continue_with',
      logo_alignment: 'center',
      width,
    })
  } catch (err: any) {
    // 国内网络无法访问 Google 时会走到这里，静默降级（按钮不出现）
    errorMsg.value = '无法连接 Google，请检查网络'
    console.warn('[GoogleLogin] 初始化失败：', err?.message || err)
  }
})
</script>
