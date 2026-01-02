<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <section>
      <h3 class="text-lg font-semibold text-slate-900 dark:text-slate-100">主页背景</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">自定义您的主页背景，支持渐变背景和图片背景</p>
      <div class="mt-4">
        <BackgroundSetting
          :background-path="userStore.account?.userSetting?.bacSetting?.bacImgFile?.currentName"
          :background-config="userStore.account?.userSetting?.bacSetting"
          @update="userStore.refreshUserInfo()" />
      </div>
    </section>
  </div>
</template>

<script lang="ts" setup>
import BackgroundSetting from './background/Setting.vue'

const userStore = useUserStore()
</script>

