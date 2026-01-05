<template>
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
        @click="selectImagePreset(preset)">
        <img :src="preset.url" alt="背景图片" class="h-full w-full object-cover" />
        <span
          v-if="preset.isSystem"
          class="absolute left-1 top-1 rounded bg-white/80 px-1 text-[10px] text-slate-600 shadow">
          系统
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

      <ImageBackgroundUploader />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'
import { BackgroundType, type BacSettingVO } from '@typing'
import { selectBackground } from '@api'
import { getImageUrl, getImageUrlByUserFile } from '@config/image.config'
import ImageBackgroundUploader from './ImageBackgroundUploader.vue'
import { usePreferenceStore } from '@stores/preference.store'

const preferenceStore = usePreferenceStore()

const selectedImageId = ref<string | null>(null)
const applyingImageId = ref<string | null>(null)

const uploading = computed(() => preferenceStore.imageBackgroundUploading)

// 从 store 直接取最新的背景配置，减少对父组件 props 的依赖
const backgroundConfigComputed = computed<BacSettingVO | null>(() => preferenceStore.preference?.imgBacShow ?? null)
const backgroundPathComputed = computed<string | null>(
  () => backgroundConfigComputed.value?.bacImgFile?.fullName ?? null
)

type ImagePreset = {
  id: string
  url: string
  isSystem: boolean
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

  // 系统预设 + 用户自定义预设，保持原有顺序
  ;(preferenceStore.defaultImageBackgroundsList ?? []).forEach((img) =>
    addPreset(mapImageToPreset(img, true)),
  )
  ;(preferenceStore.userImageBackgroundsList ?? []).forEach((img) =>
    addPreset(mapImageToPreset(img, false)),
  )
  // 若当前选中的背景不在列表中，则追加在末尾，不打乱已有顺序
  addPreset(mapImageToPreset(backgroundConfigComputed.value?.bacImgFile, false))
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

async function selectImagePreset(preset: ImagePreset) {
  applyingImageId.value = preset.id
  try {
    const setting = await selectBackground({ type: BackgroundType.IMAGE, backgroundId: preset.id })
    preferenceStore.upsertPreferenceBackground(setting)
    selectedImageId.value = extractImageId(setting.bacImgFile) ?? setting.backgroundLinkId ?? preset.id
    ElNotification.success({ message: '已应用图片背景' })

  } catch (error: any) {
    ElMessage.error(error.message || '应用图片背景失败，请重试')
  } finally {
    applyingImageId.value = null
  }
}
</script>