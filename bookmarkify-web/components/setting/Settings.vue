<template>
  <div>
    <!-- 主页背景设置 -->
    <div class="setting-section">
      <h3 class="setting-section-title">主页背景</h3>
      <p class="setting-section-desc">自定义您的主页背景，支持渐变背景和图片背景</p>
      <BackgroundUpload
        :background-path="userStore.account?.backgroundPath"
        :background-config="userStore.account?.backgroundConfig"
        @update="handleBackgroundUpdate"
        @reset="handleBackgroundReset" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { useUserStore } from '@stores/user.store'
import BackgroundUpload from './BackgroundUpload.vue'

const userStore = useUserStore()

onMounted(() => {})

async function handleBackgroundUpdate(config: any) {
  // 背景已通过 API 更新，刷新用户信息即可
  // TODO 刷新设置信息
}

async function handleBackgroundReset() {
  // TODO: 调用 API 重置背景
  // 暂时先刷新用户信息
  // TODO 刷新设置信息
}
</script>

<style scoped>
.setting-title {
  font-size: 1.5rem;
  font-weight: 600;
  margin-bottom: 2rem;
  color: #1f2937;
}

.setting-section-title {
  font-size: 1.25rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
  color: #1f2937;
}

.setting-section-desc {
  font-size: 0.875rem;
  color: #6b7280;
  margin-bottom: 1.5rem;
}
</style>
