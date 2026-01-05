<template>
  <div class="avatar-upload-container">
    <div class="relative inline-flex items-center justify-center group">
      <AvatarPreview
        :avatar-path="previewUrl || avatarPath"
        :fallback-text="fallbackInitial"
        :show-icon-fallback="showAccountIcon"
        class="transition duration-200 group-hover:brightness-[0.7]" />
      <button
        type="button"
        class="absolute inset-0 flex items-center justify-center rounded-full bg-black/40 text-white opacity-0 transition-opacity duration-500 group-hover:opacity-100"
        @click.stop.prevent="triggerSelect"
        :disabled="uploading">
        <span class="icon--memory-upload icon-size-32"></span>
      </button>
      <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="handleFileChange" />
    </div>

    <div class="mt-4 flex gap-2 justify-center" v-if="previewUrl">
      <button @click="handleUpload" :disabled="uploading" class="cy-btn cy-btn-accent">
        <span v-if="uploading">上传中...</span>
        <span v-else>确认上传</span>
      </button>
      <button @click="handleCancel" :disabled="uploading" class="cy-btn cy-btn-ghost">取消</button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { uploadAvatar } from '@api'
import { useAuthStore } from '@stores/auth.store'
import { imageConfig } from '@config/image.config'
import AvatarPreview from './AvatarPreview.vue'

interface Props {
  avatarPath?: string | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'update', avatarPath: string): void
}>()

const authStore = useAuthStore()
const fileInputRef = ref<HTMLInputElement>()
const previewUrl = ref<string | null>(null)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const fallbackInitial = computed(() => {
  const name = authStore.account?.nickName?.trim()
  if (!name) return '用'
  return name.slice(0, 1)
})

const showAccountIcon = computed(() => !previewUrl.value && !props.avatarPath)

// 预览选择的图片
function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]

  if (!file) return

  // 验证文件类型
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件')
    return
  }

  // 验证文件大小
  if (file.size > imageConfig.maxImageSize) {
    const maxSizeMB = imageConfig.maxImageSize / (1024 * 1024)
    ElMessage.error(`图片大小不能超过 ${maxSizeMB}MB`)
    return
  }

  selectedFile.value = file

  // 创建预览 URL
  const reader = new FileReader()
  reader.onload = (e) => {
    previewUrl.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
}

// 上传头像
async function handleUpload() {
  if (!selectedFile.value) return

  uploading.value = true
  try {
    const avatarPath = await uploadAvatar(selectedFile.value)
    emit('update', avatarPath)
    ElNotification.success({ message: '头像上传成功' })

    // 重置状态
    previewUrl.value = null
    selectedFile.value = null
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }

    // 刷新用户信息
    await authStore.refreshUserInfo()
  } catch (error: any) {
    ElMessage.error(error.message || '头像上传失败，请重试')
  } finally {
    uploading.value = false
  }
}

function triggerSelect() {
  fileInputRef.value?.click()
}

// 取消上传
function handleCancel() {
  previewUrl.value = null
  selectedFile.value = null
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}
</script>

<style scoped>
.avatar-upload-container {
  display: flex;
  flex-direction: column;
  align-items: center;
}
</style>
