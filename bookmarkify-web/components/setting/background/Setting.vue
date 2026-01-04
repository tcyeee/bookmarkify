<template>
  <div class="flex flex-col items-center gap-6">
    <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="handleFileChange" />
    <BackgroundPreview
      :background-path="previewUrl || (currentType === BackgroundType.IMAGE ? backgroundPath : null)"
      :background-config="previewConfig" />

    <GradientConfig
      class="w-full max-w-2xl"
      :colors="gradientColors"
      :direction="gradientDirection"
      :presets="gradientPresets"
      :saving="saving"
      :has-background="hasBackground"
      @update:colors="setGradientColors"
      @update:direction="setGradientDirection"
      @save="saveGradient"
      @reset="handleReset" />

    <div class="w-full max-w-2xl space-y-3">
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
          <div class="h-full w-full flex items-center justify-center text-slate-500 dark:text-slate-300 text-xl font-semibold">
            ＋
          </div>
        </button>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'
import { BackgroundType, UserFileType, type BacSettingVO } from '@typing'
import { resetBacBackground, selectBackground, updateBacColor, uploadBacPic } from '@api'
import { getCurrentEnvironment } from '@utils'
import { getImageUrl, getImageUrlByUserFile, imageConfig } from '@config/image.config'
import BackgroundPreview from './Preview.vue'
import GradientConfig from '../background/GradientConfig.vue'

const sysStore = useSysStore()
const userStore = useUserStore()

interface Props {
  backgroundPath?: string | null
  backgroundConfig?: BacSettingVO | null
}

const props = defineProps<Props>()
const emit = defineEmits<{ (e: 'update', config: BacSettingVO): void }>()

const fileInputRef = ref<HTMLInputElement | null>(null)
const previewUrl = ref<string | null>(null)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const saving = ref(false)
const selectedImageId = ref<string | null>(null)
const applyingImageId = ref<string | null>(null)

const currentType = ref<BackgroundType>(
  props.backgroundConfig?.type || (props.backgroundPath ? BackgroundType.IMAGE : BackgroundType.GRADIENT)
)

const gradientColors = ref<string[]>(['#a69f9f', '#c1baba', '#8f9ea6'])
const gradientDirection = ref<number>(135)
const gradientPresets = computed(() => [
  ...(sysStore.defaultGradientBackgroundsList ?? []).map((g) => ({ ...g, isSystem: true })),
  ...(sysStore.userGradientBackgroundsList ?? []).map((g) => ({ ...g, isSystem: false })),
])

type ImagePreset = {
  id: string
  url: string
  isSystem: boolean
  isPreview?: boolean
  raw?: any
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

  if (previewUrl.value) addPreset({ id: '__preview__', url: previewUrl.value, isSystem: false, isPreview: true })
  addPreset(mapImageToPreset(props.backgroundConfig?.bacImgFile, false))
  ;(sysStore.defaultImageBackgroundsList ?? []).forEach((img) => addPreset(mapImageToPreset(img, true)))
  ;(sysStore.userImageBackgroundsList ?? []).forEach((img) => addPreset(mapImageToPreset(img, false)))
  return presets
})

const activeImageKey = computed(() => {
  if (previewUrl.value) return '__preview__'
  if (selectedImageId.value) return selectedImageId.value
  const fileId = extractImageId(props.backgroundConfig?.bacImgFile)
  if (fileId) return fileId
  if (props.backgroundPath) return props.backgroundPath
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

function initGradientConfig() {
  if (props.backgroundConfig?.type === BackgroundType.GRADIENT) {
    gradientColors.value = [...props.backgroundConfig.bacColorGradient!]
    gradientDirection.value = props.backgroundConfig.bacColorDirection! || 135
  }
}

onMounted(async () => {
  initGradientConfig()
  initImageSelection(props.backgroundConfig)
  await sysStore.refreshSystemConfig()
})

watch(
  () => props.backgroundConfig,
  (newConfig) => {
    if (newConfig) {
      currentType.value = newConfig.type || (props.backgroundPath ? BackgroundType.IMAGE : BackgroundType.GRADIENT)
      initGradientConfig()
      initImageSelection(newConfig)
    }
  },
  { deep: true }
)

const previewConfig = computed<BacSettingVO | null>(() => {
  if (currentType.value === BackgroundType.GRADIENT) {
    return {
      type: BackgroundType.GRADIENT,
      bacColorGradient: gradientColors.value,
      bacColorDirection: gradientDirection.value,
    }
  }
  if (currentType.value === BackgroundType.IMAGE && previewUrl.value) {
    return null
  }
  if (props.backgroundConfig) {
    return props.backgroundConfig
  }
  return null
})

const hasBackground = computed(() => !!(props.backgroundPath || props.backgroundConfig))

watch(
  () => currentType.value,
  (type) => {
    if (type === BackgroundType.IMAGE) {
      previewUrl.value = null
      selectedFile.value = null
    }
  }
)

function setGradientColors(colors: string[]) {
  currentType.value = BackgroundType.GRADIENT
  gradientColors.value = colors
}

function setGradientDirection(direction: number) {
  currentType.value = BackgroundType.GRADIENT
  gradientDirection.value = direction
}

async function saveGradient() {
  saving.value = true
  try {
    await updateBacColor({
      colors: gradientColors.value,
      direction: gradientDirection.value,
    })

    const bacSettingVO: BacSettingVO = {
      type: BackgroundType.GRADIENT,
      bacColorGradient: gradientColors.value,
      bacColorDirection: gradientDirection.value,
    }
    emit('update', bacSettingVO)
    currentType.value = BackgroundType.GRADIENT

    ElNotification.success({ message: '渐变背景保存成功' })
  } catch (error: any) {
    ElMessage.error(error.message || '保存失败，请重试')
  } finally {
    saving.value = false
  }
}

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]

  if (!file) return

  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件')
    return
  }

  if (file.size > imageConfig.maxImageSize) {
    const maxSizeMB = imageConfig.maxImageSize / (1024 * 1024)
    ElMessage.error(`图片大小不能超过 ${maxSizeMB}MB`)
    return
  }

  selectedFile.value = file
  currentType.value = BackgroundType.IMAGE
  selectedImageId.value = '__preview__'

  const reader = new FileReader()
  reader.onload = (e) => {
    previewUrl.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
}

async function handleUpload() {
  if (!selectedFile.value) return

  uploading.value = true
  try {
    const imagePath = await uploadBacPic(selectedFile.value)

    const newConfig: BacSettingVO = {
      type: BackgroundType.IMAGE,
      bacImgFile: {
        environment: getCurrentEnvironment(),
        currentName: imagePath,
        type: UserFileType.BACKGROUND,
      },
    }

    emit('update', newConfig)
    currentType.value = BackgroundType.IMAGE
    ElNotification.success({ message: '背景上传成功' })

    previewUrl.value = null
    selectedFile.value = null
    if (fileInputRef.value) fileInputRef.value.value = ''

    await Promise.all([sysStore.refreshSystemConfig(), userStore.refreshUserInfo()])
    selectedImageId.value = extractImageId(userStore.backgroundSetting?.bacImgFile)
  } catch (error: any) {
    ElMessage.error(error.message || '背景上传失败，请重试')
  } finally {
    uploading.value = false
  }
}

function handleCancel() {
  previewUrl.value = null
  selectedFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
  initImageSelection(props.backgroundConfig)
}

async function handleReset() {
  await resetBacBackground()
  await Promise.all([sysStore.refreshSystemConfig(), userStore.refreshUserInfo()])
  initGradientConfig()
  initImageSelection(userStore.backgroundSetting)
}

function openImagePicker() {
  if (fileInputRef.value) {
    fileInputRef.value.click()
    return
  }
}

async function selectImagePreset(preset: ImagePreset) {
  if (preset.isPreview) return
  applyingImageId.value = preset.id
  try {
    await selectBackground({ type: BackgroundType.IMAGE, backgroundId: preset.id })
    await Promise.all([sysStore.refreshSystemConfig(), userStore.refreshUserInfo()])
    selectedImageId.value = preset.id
    currentType.value = BackgroundType.IMAGE
    previewUrl.value = null
    selectedFile.value = null
    if (fileInputRef.value) fileInputRef.value.value = ''
    ElNotification.success({ message: '已应用图片背景' })
  } catch (error: any) {
    ElMessage.error(error.message || '应用图片背景失败，请重试')
  } finally {
    applyingImageId.value = null
  }
}
</script>
