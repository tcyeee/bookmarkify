<template>
  <div class="flex flex-col gap-4">
    <BackgroundPreview />
    <GradientConfig />
    <ImageBackgroundSettings />
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
