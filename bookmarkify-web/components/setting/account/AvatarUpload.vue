<template>
  <div class="avatar-upload-container">
    <AvatarPreview :avatar-path="previewUrl || avatarPath" />
    <div class="mt-4 flex gap-2">
      <label class="cy-btn cy-btn-soft cursor-pointer">
        <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="handleFileChange" />
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
        </svg>
        <span>选择头像</span>
      </label>
      <button v-if="previewUrl" @click="handleUpload" :disabled="uploading" class="cy-btn cy-btn-accent">
        <span v-if="uploading">上传中...</span>
        <span v-else>确认上传</span>
      </button>
      <button v-if="previewUrl" @click="handleCancel" :disabled="uploading" class="cy-btn cy-btn-ghost">取消</button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { uploadAvatar } from '@api'
import { useUserStore } from '@stores/user.store'
import { imageConfig } from '@config/image.config'
import AvatarPreview from './AvatarPreview.vue'

interface Props {
  avatarPath?: string | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'update', avatarPath: string): void
}>()

const userStore = useUserStore()
const fileInputRef = ref<HTMLInputElement>()
const previewUrl = ref<string | null>(null)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)

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
    await userStore.refreshUserInfo()
  } catch (error: any) {
    ElMessage.error(error.message || '头像上传失败，请重试')
  } finally {
    uploading.value = false
  }
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
