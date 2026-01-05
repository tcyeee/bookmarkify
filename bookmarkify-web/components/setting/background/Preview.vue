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
import { BackgroundType, type BacSettingVO } from '@typing'
import { getImageUrl, getImageUrlByUserFile } from '@config'

const userStore = useUserStore()

// 优先使用当前偏好中的背景设置，其次兜底账号信息中的历史字段
const backgroundSetting = computed<BacSettingVO | null>(
  () => userStore.preference?.imgBacShow ?? (userStore.account as any)?.userSetting?.bacSetting ?? null
)

const backgroundUrl = computed(() => {
  const cfg = backgroundSetting.value
  if (cfg?.type === BackgroundType.IMAGE && cfg.bacImgFile) {
    const file: any = cfg.bacImgFile
    // 优先使用服务端回填的完整地址
    if (file.fullName) return file.fullName as string
    // 尝试使用标准 UserFile 字段
    if (file.environment && file.currentName) return getImageUrlByUserFile(file)
    // 退化到 currentName 的兜底解析
    if (file.currentName) return getImageUrl(file.currentName)
    return null
  }
  return null
})

const previewStyle = computed(() => {
  const cfg = backgroundSetting.value
  if (cfg?.type === BackgroundType.GRADIENT && cfg.bacColorGradient) {
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
