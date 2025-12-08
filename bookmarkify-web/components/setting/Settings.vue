<template>
  <div>
    <!-- 主页背景设置 -->
    <div class="setting-section">
      <h3 class="setting-section-title">主页背景</h3>
      <p class="setting-section-desc">自定义您的主页背景，支持渐变背景和图片背景</p>
      <BackgroundUpload
        :background-path="userStore.account?.userSetting?.bacSetting?.bacImgFile?.currentName"
        :background-config="userStore.account?.userSetting?.bacSetting"
        @update="userStore.refreshUserInfo()" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import BackgroundUpload from './BackgroundUpload.vue'

const userStore = useUserStore()
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
