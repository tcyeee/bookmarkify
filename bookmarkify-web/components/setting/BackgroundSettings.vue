<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <section>
      <h3 class="text-lg font-semibold text-slate-900 dark:text-slate-100">主页背景</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">自定义您的主页背景，支持渐变背景和图片背景</p>
      <div class="mt-4">
        <div class="flex flex-col items-center gap-6">
          <BackgroundPreview />
          <GradientConfig />
          <ImageBackgroundSettings />
        </div>
      </div>
    </section>
  </div>
</template>

<script lang="ts" setup>
import { onMounted } from 'vue'
import BackgroundPreview from './background/Preview.vue'
import GradientConfig from './background/GradientConfig.vue'
import ImageBackgroundSettings from './background/ImageBackgroundSettings.vue'
import { usePreferenceStore } from '@stores/preference.store'

const preferenceStore = usePreferenceStore()
onMounted(async () => {
  await preferenceStore.refreshBackgroundConfig()
})
</script>
