<template>
  <div class="flex flex-col items-center gap-6">
    <BackgroundPreview
      :background-path="previewUrl || (currentType === BackgroundType.IMAGE ? backgroundPath : null)"
      :background-config="previewConfig" />

    <TypeSelector v-model="currentType" class="w-full max-w-2xl" />

    <GradientConfig
      v-if="currentType === BackgroundType.GRADIENT"
      class="w-full max-w-2xl"
      :colors="gradientColors"
      :direction="gradientDirection"
      :presets="sysStore.defaultGradientBackgroundsList"
      :saving="saving"
      :has-background="hasBackground"
      @update:colors="setGradientColors"
      @update:direction="setGradientDirection"
      @save="saveGradient"
      @reset="handleReset" />

    <ImageUploader
      v-else
      class="w-full max-w-2xl"
      :preview-url="previewUrl"
      :background-path="backgroundPath"
      :uploading="uploading"
      :file-input-ref="fileInputRef"
      @file-change="handleFileChange"
      @upload="handleUpload"
      @cancel="handleCancel"
      @reset="handleReset" />
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'
import { BackgroundType, UserFileType, type BacSettingVO } from '@typing'
import { resetBacBackground, updateBacColor, uploadBacPic } from '@api'
import { getCurrentEnvironment } from '@utils'
import { imageConfig } from '@config/image.config'
import BackgroundPreview from './Preview.vue'
import GradientConfig from '../background/GradientConfig.vue'
import ImageUploader from '../background/ImageUploader.vue'
import TypeSelector from '../background/TypeSelector.vue'

const sysStore = useSysStore()
const userStore = useUserStore()

interface Props {
  backgroundPath?: string | null
  backgroundConfig?: BacSettingVO | null
}

const props = defineProps<Props>()
const emit = defineEmits<{ (e: 'update', config: BacSettingVO): void }>()

const fileInputRef = ref<Ref<HTMLInputElement | null>>()
const previewUrl = ref<string | null>(null)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const saving = ref(false)

const currentType = ref<BackgroundType>(
  props.backgroundConfig?.type || (props.backgroundPath ? BackgroundType.IMAGE : BackgroundType.GRADIENT)
)

const gradientColors = ref<string[]>(['#a69f9f', '#c1baba', '#8f9ea6'])
const gradientDirection = ref<number>(135)

function initGradientConfig() {
  if (props.backgroundConfig?.type === BackgroundType.GRADIENT) {
    gradientColors.value = [...props.backgroundConfig.bacColorGradient!]
    gradientDirection.value = props.backgroundConfig.bacColorDirection! || 135
  }
}

onMounted(initGradientConfig)

watch(
  () => props.backgroundConfig,
  (newConfig) => {
    if (newConfig) {
      currentType.value = newConfig.type || (props.backgroundPath ? BackgroundType.IMAGE : BackgroundType.GRADIENT)
      initGradientConfig()
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
  gradientColors.value = colors
}

function setGradientDirection(direction: number) {
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
    ElNotification.success({ message: '背景上传成功' })

    previewUrl.value = null
    selectedFile.value = null
    if (fileInputRef.value) fileInputRef.value.value = null
  } catch (error: any) {
    ElMessage.error(error.message || '背景上传失败，请重试')
  } finally {
    uploading.value = false
  }
}

function handleCancel() {
  previewUrl.value = null
  selectedFile.value = null
  if (fileInputRef.value) fileInputRef.value.value = null
}

async function handleReset() {
  await resetBacBackground()
  await userStore.refreshUserInfo()
}
</script>
