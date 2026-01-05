<template>
  <div>
    <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="handleFileChange" />
    <button
      type="button"
      class="group relative w-full aspect-square overflow-hidden rounded-lg border-2 border-dashed border-slate-200 transition-all shadow-sm hover:-translate-y-0.5 hover:shadow-md dark:border-slate-700 dark:hover:border-slate-700"
      :disabled="uploading"
      @click="openImagePicker">
      <div
        class="h-full w-full flex items-center justify-center text-slate-500 dark:text-slate-300 text-xl font-semibold select-none cursor-pointer">
        ＋
      </div>
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { uploadBacPic } from '@api'
import { imageConfig } from '@config/image.config'

const sysStore = useSysStore()
const userStore = useUserStore()

const fileInputRef = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)

const uploading = computed(() => sysStore.imageBackgroundUploading)
const maxSizeMB = computed(() => imageConfig.maxImageSize / (1024 * 1024))

function resetFileInput() {
  selectedFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
}

function validateSelectedFile(file: File | null | undefined): file is File {
  if (!file) return false

  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件')
    resetFileInput()
    return false
  }

  if (file.size > imageConfig.maxImageSize) {
    ElMessage.error(`图片大小不能超过 ${maxSizeMB.value}MB`)
    resetFileInput()
    return false
  }

  return true
}

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]

  if (!validateSelectedFile(file)) return

  selectedFile.value = file
  uploadSelectedFile()
}

async function uploadSelectedFile() {
  if (!selectedFile.value || uploading.value) return

  sysStore.setImageBackgroundUploading(true)
  try {
    await uploadBacPic(selectedFile.value)
    ElNotification.success({ message: '背景上传成功' })

    resetFileInput()
    await Promise.all([sysStore.refreshSystemConfig(), userStore.refreshUserInfo()])
  } catch (error: any) {
    ElMessage.error(error.message || '背景上传失败，请重试')
  } finally {
    sysStore.setImageBackgroundUploading(false)
  }
}

function openImagePicker() {
  if (fileInputRef.value) {
    fileInputRef.value.click()
  }
}
</script>

