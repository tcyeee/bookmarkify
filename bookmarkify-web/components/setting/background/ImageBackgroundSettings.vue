<template>
  <div class="w-full max-w-2xl space-y-3">
    <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="handleFileChange" />
    <div class="text-sm font-medium text-slate-700 dark:text-slate-200">图片背景</div>
    <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5">
      <button
        v-for="preset in imagePresets"
        :key="preset.id"
        type="button"
        :class="[
          'group relative aspect-square overflow-hidden rounded-lg border-2 transition-all shadow-sm',
          isImageActive(preset)
            ? 'border-blue-500 ring-2 ring-blue-200 ring-offset-2 ring-offset-white dark:ring-offset-slate-900'
            : 'border-transparent hover:-translate-y-0.5 hover:shadow-md dark:hover:border-slate-700',
        ]"
        :disabled="uploading || applyingImageId === preset.id"
        @click="preset.isPreview ? openImagePicker() : selectImagePreset(preset)">
        <img :src="preset.url" alt="背景图片" class="h-full w-full object-cover" />
        <span
          v-if="preset.isSystem"
          class="absolute left-1 top-1 rounded bg-white/80 px-1 text-[10px] text-slate-600 shadow">
          系统
        </span>
        <span
          v-else-if="preset.isPreview"
          class="absolute left-1 top-1 rounded bg-blue-600/90 px-1 text-[10px] text-white shadow">
          待上传
        </span>
        <span
          v-else
          class="absolute left-1 top-1 rounded bg-emerald-500/90 px-1 text-[10px] text-white shadow">
          自定义
        </span>

        <div
          v-if="applyingImageId === preset.id"
          class="absolute inset-0 flex items-center justify-center bg-black/30 text-white text-sm">
          应用中...
        </div>
      </button>

      <button
        type="button"
        class="aspect-square rounded-lg border-2 transition-all shadow-sm border-dashed border-slate-200 hover:-translate-y-0.5 hover:shadow-md dark:border-slate-700"
        :disabled="uploading"
        @click="openImagePicker">
        <div class="h-full w-full flex items-center justify-center text-slate-500 dark:text-slate-300 text-xl font-semibold select-none cursor-pointer">
          ＋
        </div>
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'
import { BackgroundType, UserFileType, type BacSettingVO } from '@typing'
import { selectBackground, uploadBacPic } from '@api'
import { getCurrentEnvironment } from '@utils'
import { getImageUrl, getImageUrlByUserFile, imageConfig } from '@config/image.config'

const sysStore = useSysStore()
const userStore = useUserStore()

const fileInputRef = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const selectedImageId = ref<string | null>(null)
const applyingImageId = ref<string | null>(null)

const maxSizeMB = computed(() => imageConfig.maxImageSize / (1024 * 1024))

// 从 store 直接取最新的背景配置，减少对父组件 props 的依赖
const backgroundConfigComputed = computed<BacSettingVO | null>(() => userStore.preference?.imgBacShow ?? null)
const backgroundPathComputed = computed<string | null>(
  () => backgroundConfigComputed.value?.bacImgFile?.fullName ?? null
)

type ImagePreset = {
  id: string
  url: string
  isSystem: boolean
  raw?: any
  isPreview?: boolean
}

function extractImageId(file: any): string | null {
  if (!file) return null
  const id = file.id ?? file.currentName
  return id ? String(id) : null
}

function resolveImageUrl(file: any): string | null {
  if (!file) return null
  if (file.fullName) return file.fullName as string
  if (file.environment && file.currentName) return getImageUrlByUserFile(file)
  if (file.currentName) return getImageUrl(file.currentName)
  return null
}

function mapImageToPreset(file: any, isSystem: boolean): ImagePreset | null {
  if (!file) return null
  const id = String(file.id ?? file.currentName ?? file.fullName ?? '')
  const url = resolveImageUrl(file)
  if (!id || !url) return null
  return { id, url, isSystem, raw: file }
}

const imagePresets = computed<ImagePreset[]>(() => {
  const presets: ImagePreset[] = []
  const seen = new Set<string>()
  const addPreset = (preset: ImagePreset | null) => {
    if (!preset || seen.has(preset.id)) return
    seen.add(preset.id)
    presets.push(preset)
  }

  // 已有背景 + 系统预设 + 用户自定义预设
  addPreset(mapImageToPreset(backgroundConfigComputed.value?.bacImgFile, false))
  ;(sysStore.defaultImageBackgroundsList ?? []).forEach((img) => addPreset(mapImageToPreset(img, true)))
  ;(sysStore.userImageBackgroundsList ?? []).forEach((img) => addPreset(mapImageToPreset(img, false)))
  return presets
})

const activeImageKey = computed(() => {
  if (selectedImageId.value) return selectedImageId.value
  const fileId = extractImageId(backgroundConfigComputed.value?.bacImgFile)
  if (fileId) return fileId
  if (backgroundPathComputed.value) return backgroundPathComputed.value
  return null
})

function isImageActive(preset: ImagePreset) {
  const key = activeImageKey.value
  if (!key) return false
  return preset.id === key || preset.url === key || preset.raw?.currentName === key
}

function initImageSelection(config?: BacSettingVO | null) {
  if (config?.type === BackgroundType.IMAGE && config.bacImgFile) {
    selectedImageId.value = extractImageId(config.bacImgFile)
  } else {
    selectedImageId.value = null
  }
}

onMounted(() => {
  initImageSelection(backgroundConfigComputed.value)
})

watch(
  () => backgroundConfigComputed.value,
  (newConfig) => {
    if (newConfig) {
      initImageSelection(newConfig)
    }
  },
  { deep: true }
)

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
  // 选择后直接上传，预览交由 store 刷新结果
  uploadSelectedFile()
}

async function uploadSelectedFile() {
  if (!selectedFile.value || uploading.value) return

  uploading.value = true
  try {
    const imagePath = await uploadBacPic(selectedFile.value)

    ElNotification.success({ message: '背景上传成功' })

    resetFileInput()
    await Promise.all([sysStore.refreshSystemConfig(), userStore.refreshUserInfo()])
  } catch (error: any) {
    ElMessage.error(error.message || '背景上传失败，请重试')
  } finally {
    uploading.value = false
  }
}

function openImagePicker() {
  if (fileInputRef.value) {
    fileInputRef.value.click()
    return
  }
}

async function selectImagePreset(preset: ImagePreset) {
  applyingImageId.value = preset.id
  try {
    const setting = await selectBackground({ type: BackgroundType.IMAGE, backgroundId: preset.id })
    userStore.upsertPreferenceBackground(setting)
    selectedImageId.value = extractImageId(setting.bacImgFile) ?? setting.backgroundLinkId ?? preset.id
    resetFileInput()
    ElNotification.success({ message: '已应用图片背景' })

  } catch (error: any) {
    ElMessage.error(error.message || '应用图片背景失败，请重试')
  } finally {
    applyingImageId.value = null
  }
}
</script>