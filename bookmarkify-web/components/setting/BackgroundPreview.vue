<template>
  <div class="background-preview-container">
    <div class="background-preview" :style="previewStyle">
      <img
        v-if="backgroundConfig?.type === 'IMAGE' && backgroundUrl"
        :src="backgroundUrl"
        alt="主页背景"
        class="preview-image" />
      <div v-else-if="backgroundConfig?.type === 'GRADIENT' && backgroundConfig.gradient" class="preview-gradient">
        <span>渐变背景</span>
      </div>
      <div v-else class="preview-placeholder">
        <span>默认背景</span>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import type { BackgroundConfig } from '@api/typing'

const props = defineProps<{
  backgroundPath?: string | null
  backgroundConfig?: BackgroundConfig | null
}>()

const backgroundUrl = computed(() => {
  // if (props.backgroundConfig?.type === 'IMAGE' && props.backgroundConfig.imagePath) {
  //   return getImageUrl(props.backgroundConfig.imagePath)
  // }
  // if (props.backgroundPath) {
  //   return getImageUrl(props.backgroundPath)
  // }
  // return null
})

const previewStyle = computed(() => {
  if (props.backgroundConfig?.type === 'GRADIENT' && props.backgroundConfig.gradient) {
    const colors = props.backgroundConfig.gradient.colors.join(', ')
    const direction = props.backgroundConfig.gradient.direction || 135
    return {
      backgroundImage: `linear-gradient(${direction}deg, ${colors})`,
    }
  }
  return {}
})
</script>

<style scoped>
.background-preview-container {
  display: flex;
  justify-content: center;
  margin-bottom: 1rem;
}

.background-preview {
  width: 100%;
  min-width: 40vw;
  aspect-ratio: 4 / 3;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  border: 2px solid #e5e7eb;
}

.preview-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preview-gradient {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 1.2rem;
  font-weight: 500;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.3);
}

.preview-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #a69f9f, #c1baba, #8f9ea6);
  color: white;
  font-size: 1.2rem;
  font-weight: 500;
}
</style>
