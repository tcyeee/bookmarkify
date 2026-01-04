<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <section>
      <h3 class="text-lg font-semibold text-slate-900 dark:text-slate-100">主页背景</h3>
      <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">自定义您的主页背景，支持渐变背景和图片背景</p>
      <div class="mt-4">
        <div class="flex flex-col items-center gap-6">
          <BackgroundPreview
            :background-path="backgroundPath"
            :background-config="backgroundConfig" />

          <GradientConfig
            class="w-full max-w-2xl"
            :has-background="hasBackground" />

          <ImageBackgroundSettings class="w-full max-w-2xl" />
        </div>
      </div>
    </section>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted } from 'vue'
import { type BacSettingVO } from '@typing'
import BackgroundPreview from './background/Preview.vue'
import GradientConfig from './background/GradientConfig.vue'
import ImageBackgroundSettings from './background/ImageBackgroundSettings.vue'

const sysStore = useSysStore()
const userStore = useUserStore()

const backgroundConfig = computed<BacSettingVO | null>(() => userStore.account?.userSetting?.bacSetting ?? null)
const backgroundPath = computed<string | null>(() => userStore.account?.userSetting?.bacSetting?.bacImgFile?.currentName ?? null)

onMounted(async () => {
  await sysStore.refreshSystemConfig()
})

const hasBackground = computed(() => !!(backgroundPath.value || backgroundConfig.value))

</script>