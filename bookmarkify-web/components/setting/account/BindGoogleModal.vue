<!-- components/setting/account/BindGoogleModal.vue -->
<template>
  <div class="flex flex-col items-end gap-1">
    <!-- 已关联：解绑按钮 -->
    <button
      v-if="props.googleEmail"
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[100px]"
      :disabled="loading || disabled"
      @click="handleUnbind">
      <span v-if="loading">处理中...</span>
      <span v-else>解绑</span>
    </button>

    <!-- 未关联：可见按钮 + 透明覆盖的 Google 官方按钮（GIS 为跨域 iframe，无法编程触发，故直接覆盖承接点击） -->
    <div v-else class="relative h-10 min-w-[100px]">
      <button
        type="button"
        class="cy-btn cy-btn-primary cy-btn-outline pointer-events-none h-10 w-full px-4"
        :disabled="loading || disabled || !clientId">
        <span>{{ loading ? '关联中...' : '关联谷歌' }}</span>
      </button>
      <!-- 官方按钮透明覆盖在上层，宽度贴合自定义按钮并裁剪溢出 -->
      <div
        ref="btnRef"
        class="absolute inset-0 flex items-center justify-center overflow-hidden opacity-0"
        :class="{ 'pointer-events-none': loading || disabled }" />
    </div>

    <p v-if="errorMsg" class="text-right text-xs text-red-400">{{ errorMsg }}</p>
  </div>
</template>

<script lang="ts" setup>
import { bindGoogle, unbindGoogle } from '@api'
import { useAuthStore } from '@stores/auth.store'

const props = defineProps<{ googleEmail?: string | null; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'success'): void }>()

const authStore = useAuthStore()
const config = useRuntimeConfig()
const clientId = (config.public.googleClientId as string | undefined) || ''

const btnRef = ref<HTMLElement>()
const loading = ref(false)
const errorMsg = ref('')
const gisInited = ref(false)

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

// 收到 Google 回调（凭据为 ID Token JWT）后走后端关联
async function handleCredential(response: { credential?: string }) {
  if (!response?.credential) {
    errorMsg.value = '未获取到 Google 凭据'
    return
  }
  errorMsg.value = ''
  loading.value = true
  try {
    const result = await bindGoogle(response.credential)
    authStore.account = { ...authStore.account, ...result } as any
    ElNotification.success({ message: 'Google 关联成功' })
    emit('success')
  } catch (err: any) {
    // http 客户端已对 1xx 业务码统一弹窗，这里仅做兜底提示
    errorMsg.value = err?.msg || 'Google 关联失败，请重试'
  } finally {
    loading.value = false
  }
}

async function renderGoogleButton() {
  if (!clientId || !btnRef.value) return
  try {
    await loadGsiScript()
    const google = (window as any).google
    if (!gisInited.value) {
      google.accounts.id.initialize({ client_id: clientId, callback: handleCredential })
      gisInited.value = true
    }
    btnRef.value.innerHTML = ''
    const width = Math.min(Math.max(btnRef.value.clientWidth || 200, 200), 400)
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
    // 国内网络无法访问 Google 时会走到这里
    errorMsg.value = '无法连接 Google，请检查网络'
    console.warn('[BindGoogle] 初始化失败：', err?.message || err)
  }
}

async function handleUnbind() {
  try {
    await ElMessageBox.confirm('解绑后将无法用此 Google 账号登录，确定解绑吗？', '解绑 Google', {
      confirmButtonText: '解绑',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  loading.value = true
  try {
    const result = await unbindGoogle()
    authStore.account = { ...authStore.account, ...result } as any
    ElNotification.success({ message: '已解绑 Google' })
    emit('success')
  } catch {
    // http 客户端已统一提示
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (!import.meta.client) return
  // 未关联时渲染官方按钮承接点击；已关联时无需渲染
  if (!props.googleEmail) renderGoogleButton()
})

// 解绑成功后切回未关联态，需要补渲染官方按钮
watch(
  () => props.googleEmail,
  async (val) => {
    if (val) return
    await nextTick()
    renderGoogleButton()
  },
)
</script>
