<template>
  <div class="background-upload-container">
    <BackgroundPreview
      :background-path="previewUrl || (currentType === BackgroundType.IMAGE ? backgroundPath : null)"
      :background-config="previewConfig" />

    <!-- 背景类型选择 -->
    <div class="type-selector mb-4">
      <label class="type-label">背景类型：</label>
      <div class="type-buttons">
        <button
          :class="['type-btn', { 'type-btn-active': currentType === BackgroundType.GRADIENT }]"
          @click="switchType(BackgroundType.GRADIENT)">
          渐变背景
        </button>
        <button
          :class="['type-btn', { 'type-btn-active': currentType === BackgroundType.IMAGE }]"
          @click="switchType(BackgroundType.IMAGE)">
          图片背景
        </button>
      </div>
    </div>

    <!-- 渐变背景配置 -->
    <div v-if="currentType === BackgroundType.GRADIENT" class="gradient-config">
      <div class="config-section">
        <label class="config-label">预设渐变：</label>
        <div class="preset-gradients">
          <div
            v-for="(preset, index) in presetGradients"
            :key="index"
            class="preset-item"
            :class="{ 'preset-item-active': isPresetActive(preset) }"
            :style="{ backgroundImage: `linear-gradient(135deg, ${preset.colors.join(', ')})` }"
            @click="selectPreset(preset)" />
        </div>
      </div>

      <div class="config-section mt-4">
        <label class="config-label">自定义渐变：</label>
        <div class="color-inputs">
          <div v-for="(color, index) in gradientColors" :key="index" class="color-input-group">
            <label class="color-label">颜色 {{ index + 1 }}：</label>
            <input v-model="gradientColors[index]" type="color" class="color-picker" />
            <input v-model="gradientColors[index]" type="text" class="color-text" placeholder="#000000" />
            <button v-if="gradientColors.length > 2" @click="removeColor(index)" class="remove-color-btn">删除</button>
          </div>
        </div>
        <button @click="addColor" class="add-color-btn">+ 添加颜色</button>
      </div>

      <div class="config-section mt-4">
        <label class="config-label">渐变方向：</label>
        <input v-model.number="gradientDirection" type="range" min="0" max="360" step="1" class="direction-slider" />
        <div class="direction-value">{{ gradientDirection }}°</div>
      </div>

      <div class="action-buttons mt-4">
        <button @click="saveGradient" :disabled="saving" class="cy-btn cy-btn-accent">
          <span v-if="saving">保存中...</span>
          <span v-else>保存渐变背景</span>
        </button>
        <button v-if="hasBackground" @click="handleReset" :disabled="saving" class="cy-btn cy-btn-ghost">恢复默认</button>
      </div>
    </div>

    <!-- 图片背景配置 -->
    <div v-if="currentType === BackgroundType.IMAGE" class="image-config">
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
          <span>选择图片</span>
        </label>
        <button v-if="previewUrl" @click="handleUpload" :disabled="uploading" class="cy-btn cy-btn-accent">
          <span v-if="uploading">上传中...</span>
          <span v-else>确认上传</span>
        </button>
        <button v-if="previewUrl" @click="handleCancel" :disabled="uploading" class="cy-btn cy-btn-ghost">取消</button>
        <button v-if="backgroundPath && !previewUrl" @click="handleReset" :disabled="uploading" class="cy-btn cy-btn-ghost">
          恢复默认
        </button>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { useUserStore } from '@stores/user.store'
import { imageConfig } from '@config/image.config'
import BackgroundPreview from './BackgroundPreview.vue'
import { BackgroundType } from '@typing'
import type { BackgroundConfig, BackgroundGradientEntity } from '@typing'
import { updateBacColor, uploadBacPic } from '@api'

interface Props {
  backgroundPath?: string | null
  backgroundConfig?: BackgroundConfig | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'update', config: BackgroundConfig): void
  (e: 'reset'): void
}>()

const userStore = useUserStore()
const fileInputRef = ref<HTMLInputElement>()
const previewUrl = ref<string | null>(null)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const saving = ref(false)

// 当前选择的背景类型
const currentType = ref<BackgroundType>(
  props.backgroundConfig?.type || (props.backgroundPath ? BackgroundType.IMAGE : BackgroundType.GRADIENT)
)

// 渐变配置
const gradientColors = ref<string[]>(['#a69f9f', '#c1baba', '#8f9ea6'])
const gradientDirection = ref<number>(135)

// 预设渐变
const presetGradients: BackgroundGradientEntity[] = [
  { colors: ['#a69f9f', '#c1baba', '#8f9ea6'], direction: 135 },
  { colors: ['#667eea', '#764ba2'], direction: 135 },
  { colors: ['#f093fb', '#f5576c'], direction: 135 },
  { colors: ['#4facfe', '#00f2fe'], direction: 135 },
  { colors: ['#43e97b', '#38f9d7'], direction: 135 },
  { colors: ['#fa709a', '#fee140'], direction: 135 },
  { colors: ['#30cfd0', '#330867'], direction: 135 },
  { colors: ['#a8edea', '#fed6e3'], direction: 135 },
  { colors: ['#ff9a9e', '#fecfef'], direction: 135 },
  { colors: ['#ffecd2', '#fcb69f'], direction: 135 },
]

// 初始化渐变配置
onMounted(() => {
  if (props.backgroundConfig?.type === BackgroundType.GRADIENT && props.backgroundConfig.gradient) {
    gradientColors.value = [...props.backgroundConfig.gradient.colors]
    gradientDirection.value = props.backgroundConfig.gradient.direction || 135
  }
})

// 预览配置
const previewConfig = computed<BackgroundConfig | null>(() => {
  if (currentType.value === BackgroundType.GRADIENT) {
    return {
      type: BackgroundType.GRADIENT,
      gradient: {
        colors: gradientColors.value,
        direction: gradientDirection.value,
      },
    }
  }
  return null
})

// 是否有背景设置
const hasBackground = computed(() => {
  return !!(props.backgroundPath || props.backgroundConfig)
})

// 切换背景类型
function switchType(type: BackgroundType) {
  currentType.value = type
  // 如果切换到图片类型，清除预览
  if (type === BackgroundType.IMAGE) {
    previewUrl.value = null
    selectedFile.value = null
  }
}

// 选择预设渐变
function selectPreset(preset: BackgroundGradientEntity) {
  gradientColors.value = [...preset.colors]
  gradientDirection.value = preset.direction || 135
}

// 检查预设是否激活
function isPresetActive(preset: BackgroundGradientEntity) {
  return (
    gradientColors.value.length === preset.colors.length &&
    gradientColors.value.every((color, index) => color === preset.colors[index]) &&
    gradientDirection.value === (preset.direction || 135)
  )
}

// 添加颜色
function addColor() {
  gradientColors.value.push('#000000')
}

// 删除颜色
function removeColor(index: number) {
  if (gradientColors.value.length > 2) {
    gradientColors.value.splice(index, 1)
  }
}

// 保存渐变背景
async function saveGradient() {
  saving.value = true
  try {
    const config: BackgroundConfig = {
      type: BackgroundType.GRADIENT,
      gradient: {
        colors: gradientColors.value,
        direction: gradientDirection.value,
      },
    }

    await updateBacColor(config.gradient!)
    emit('update', config)
    ElNotification.success({ message: '渐变背景保存成功' })

    // 刷新用户信息
    await userStore.getUserInfo()
  } catch (error: any) {
    ElMessage.error(error.message || '保存失败，请重试')
  } finally {
    saving.value = false
  }
}

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

// 上传背景图片
async function handleUpload() {
  if (!selectedFile.value) return

  uploading.value = true
  try {
    const imagePath = await uploadBacPic(selectedFile.value)
    const config: BackgroundConfig = {
      type: BackgroundType.IMAGE,
      imagePath,
    }

    emit('update', config)
    ElNotification.success({ message: '背景上传成功' })

    // 重置状态
    previewUrl.value = null
    selectedFile.value = null
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }

    // 刷新用户信息
    await userStore.getUserInfo()
  } catch (error: any) {
    ElMessage.error(error.message || '背景上传失败，请重试')
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

// 恢复默认背景
function handleReset() {
  emit('reset')
}
</script>

<style scoped>
.background-upload-container {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.type-selector {
  width: 100%;
  max-width: 600px;
}

.type-label {
  display: block;
  font-weight: 500;
  margin-bottom: 0.5rem;
  color: #374151;
}

.type-buttons {
  display: flex;
  gap: 0.5rem;
}

.type-btn {
  flex: 1;
  padding: 0.75rem 1rem;
  border: 2px solid #e5e7eb;
  border-radius: 8px;
  background: white;
  color: #6b7280;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.type-btn:hover {
  border-color: #d1d5db;
  background: #f9fafb;
}

.type-btn-active {
  border-color: #3b82f6;
  background: #3b82f6;
  color: white;
}

.gradient-config,
.image-config {
  width: 100%;
  max-width: 600px;
}

.config-section {
  margin-bottom: 1rem;
}

.config-label {
  display: block;
  font-weight: 500;
  margin-bottom: 0.75rem;
  color: #374151;
}

.preset-gradients {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 0.75rem;
}

.preset-item {
  aspect-ratio: 1;
  border-radius: 8px;
  border: 3px solid transparent;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.preset-item:hover {
  transform: scale(1.05);
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.15);
}

.preset-item-active {
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
}

.color-inputs {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.color-input-group {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.color-label {
  min-width: 70px;
  font-size: 0.875rem;
  color: #6b7280;
}

.color-picker {
  width: 60px;
  height: 40px;
  border: 2px solid #e5e7eb;
  border-radius: 6px;
  cursor: pointer;
}

.color-text {
  flex: 1;
  padding: 0.5rem;
  border: 2px solid #e5e7eb;
  border-radius: 6px;
  font-family: monospace;
}

.remove-color-btn {
  padding: 0.5rem 1rem;
  background: #ef4444;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.875rem;
}

.remove-color-btn:hover {
  background: #dc2626;
}

.add-color-btn {
  padding: 0.5rem 1rem;
  background: #f3f4f6;
  border: 2px solid #e5e7eb;
  border-radius: 6px;
  cursor: pointer;
  color: #374151;
  font-weight: 500;
}

.add-color-btn:hover {
  background: #e5e7eb;
}

.direction-slider {
  width: 100%;
  margin: 0.5rem 0;
}

.direction-value {
  text-align: center;
  font-weight: 500;
  color: #6b7280;
  margin-top: 0.25rem;
}

.action-buttons {
  display: flex;
  gap: 0.5rem;
  justify-content: center;
}
</style>
