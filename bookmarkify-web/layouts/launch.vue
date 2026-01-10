<template>
  <div
    class="relative select-none w-screen min-h-screen px-4 sm:px-[7vw] md:px-[10vw] lg:px-[12vw] pt-[10vh] overflow-x-hidden"
    :style="renderBackgroundStyle">
    <div class="w-full">
      <NuxtPage />
    </div>
    <CommonAddBookmarkFab v-if="showDesktopAddEntry" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { BackgroundType, type BacSettingVO } from '@typing'
import { getImageUrl, getImageUrlByUserFile } from '@config/image.config'
import { usePreferenceStore } from '@stores/preference.store'

const preferenceStore = usePreferenceStore()
const backgroundSetting = computed<BacSettingVO | null>(() => preferenceStore.preference?.imgBacShow ?? null)
const showDesktopAddEntry = computed<boolean>(() => preferenceStore.preference?.showDesktopAddEntry ?? true)

const backgroundUrl = computed(() => {
  const cfg = backgroundSetting.value
  if (cfg?.type === BackgroundType.IMAGE) {
    if (preferenceStore.backgroundImageDataUrl) return preferenceStore.backgroundImageDataUrl
    const file: any = cfg.bacImgFile
    if (!file) return null
    if (file.fullName) return file.fullName as string
    if (file.environment && file.currentName) return getImageUrlByUserFile(file)
    if (file.currentName) return getImageUrl(file.currentName)
  }
  return null
})

const backgroundStyle = computed(() => {
  const cfg = backgroundSetting.value
  if (cfg?.type === BackgroundType.GRADIENT && cfg.bacColorGradient?.length) {
    const colors = cfg.bacColorGradient.join(', ')
    const direction = cfg.bacColorDirection ?? 135
    return {
      backgroundImage: `linear-gradient(${direction}deg, ${colors})`,
      backgroundSize: 'cover',
      backgroundRepeat: 'no-repeat',
    }
  }

  const url = backgroundUrl.value
  if (url) {
    return {
      backgroundImage: `url('${url}')`,
      backgroundSize: 'cover',
      backgroundPosition: 'center',
      backgroundRepeat: 'no-repeat',
    }
  }

  return {
    backgroundColor: '#f8fafc',
  }
})

// 让首屏 SSR 与客户端首帧保持一致，避免 hydration style mismatch
const hydrated = ref(false)
onMounted(() => {
  hydrated.value = true
})

const renderBackgroundStyle = computed(() => (hydrated.value ? backgroundStyle.value : { backgroundColor: '#f8fafc' }))
</script>