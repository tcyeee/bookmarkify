<template>
  <div class="background-preview-container">
    <div class="background-preview" :style="previewStyle">
      <img v-if="backgroundUrl" :src="backgroundUrl" alt="主页背景" class="preview-image" />
      <div v-else-if="backgroundSetting?.type === 'GRADIENT' && backgroundSetting.bacColorGradient" class="preview-gradient">
        <span>渐变背景</span>
      </div>
      <div v-else class="preview-placeholder">
        <span>默认背景</span>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import type { BacSettingVO } from '@typing'
import { getImageUrlByUserFile } from '@config'

const userStore = useUserStore()

const backgroundSetting = computed<BacSettingVO | null>(() => userStore.backgroundSetting ?? userStore.account?.userSetting?.bacSetting ?? null)

const backgroundUrl = computed(() => {
  const cfg = backgroundSetting.value
  if (cfg?.type === 'IMAGE' && cfg.bacImgFile) {
    return getImageUrlByUserFile(cfg.bacImgFile)
  }
  return null
})

const previewStyle = computed(() => {
  const cfg = backgroundSetting.value
  if (cfg?.type === 'GRADIENT' && cfg.bacColorGradient) {
    const colors = cfg.bacColorGradient.join(', ')
    const direction = cfg.bacColorDirection || 135
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
  width: 40vw;
  aspect-ratio: 16 / 9;
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
