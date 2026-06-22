<template>
  <div class="flex items-center gap-2">
    <!-- 已关联：解绑按钮；未关联：打开关联弹窗 -->
    <button
      v-if="props.googleEmail"
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]"
      :disabled="loading || disabled"
      @click="handleUnbind">
      <span v-if="loading">处理中...</span>
      <span v-else>解绑</span>
    </button>
    <button
      v-else
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]"
      :disabled="loading || disabled || !clientId"
      @click="openDialog">
      <span>关联谷歌</span>
    </button>

    <dialog ref="dialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <h3 class="text-lg font-semibold text-slate-800 dark:text-slate-100">关联 Google 账号</h3>
        <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">
          关联后可用此 Google 账号直接登录当前账户。
        </p>

        <div class="mt-6 flex flex-col items-center gap-3">
          <!-- Google 官方按钮挂载点（GIS 会把 iframe 按钮渲染进来） -->
          <div ref="btnRef" class="min-h-[40px]" />
          <p v-if="errorMsg" class="text-center text-xs text-red-400">{{ errorMsg }}</p>
        </div>

        <div class="cy-modal-action mt-10">
          <button class="cy-btn cy-btn-ghost" @click="closeDialog" :disabled="loading">取消</button>
        </div>
      </div>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { bindGoogle, unbindGoogle } from '@api'
import { useAuthStore } from '@stores/auth.store'

const props = defineProps<{ googleEmail?: string | null; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'success'): void }>()

const sysStore = useSysStore()
const authStore = useAuthStore()
const config = useRuntimeConfig()
const clientId = (config.public.googleClientId as string | undefined) || ''

const dialogRef = ref<HTMLDialogElement>()
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
    closeDialog()
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
    const width = Math.min(Math.max(btnRef.value.clientWidth || 320, 200), 400)
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

async function openDialog() {
  if (!import.meta.client || !dialogRef.value) return
  errorMsg.value = ''
  dialogRef.value.showModal()
  sysStore.togglePreventKeyEventsFlag(true)
  await nextTick()
  renderGoogleButton()
}

function closeDialog() {
  if (!import.meta.client || !dialogRef.value) return
  handleDialogClose()
  dialogRef.value.close()
}

function handleDialogClose() {
  sysStore.togglePreventKeyEventsFlag(false)
  errorMsg.value = ''
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
  if (!import.meta.client || !dialogRef.value) return
  dialogRef.value.addEventListener('close', handleDialogClose)
  dialogRef.value.addEventListener('cancel', handleDialogClose)
})

onBeforeUnmount(() => {
  if (!import.meta.client || !dialogRef.value) return
  dialogRef.value.removeEventListener('close', handleDialogClose)
  dialogRef.value.removeEventListener('cancel', handleDialogClose)
})
</script>
