<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div>
      <div class="mb-2 text-sm font-medium text-slate-700 dark:text-slate-200">预设渐变</div>
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5">
        <button
          v-for="(preset, index) in presets"
          :key="index"
          type="button"
          :style="{ backgroundImage: `linear-gradient(135deg, ${preset.colors.join(', ')})` }"
          :class="[
            'aspect-square rounded-lg border-2 transition-all shadow-sm',
            isPresetActive(preset)
              ? 'border-blue-500 ring-2 ring-blue-200 ring-offset-2 ring-offset-white dark:ring-offset-slate-900'
              : 'border-transparent hover:-translate-y-0.5 hover:shadow-md dark:hover:border-slate-700',
          ]"
          :disabled="applyingPreset"
          @click="selectPreset(preset)" />
      </div>
    </div>

    <GradientCustom
      :colors="colors"
      :direction="direction"
      :saving="saving"
      :has-background="hasBackground"
      @update:colors="emit('update:colors', $event)"
      @update:direction="emit('update:direction', $event)"
      @save="emit('save')"
      @reset="emit('reset')" />
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { selectBackground, updateBacColor } from '@api'
import GradientCustom from './GradientCustom.vue'
import { BackgroundType, type BacSettingVO, type BackSettingParams, type BacGradientVO } from '@typing'

const userStore = useUserStore()
const applyingPreset = ref(false)

const props = defineProps<{
  colors: string[]
  direction: number
  presets: BacGradientVO[]
  saving: boolean
  hasBackground: boolean
}>()

const emit = defineEmits<{
  (e: 'update:colors', value: string[]): void
  (e: 'update:direction', value: number): void
  (e: 'save'): void
  (e: 'reset'): void
}>()

async function applyPresetBackground(preset: BacGradientVO) {
  const setting: BacSettingVO = {
    type: BackgroundType.GRADIENT,
    bacColorGradient: [...preset.colors],
    bacColorDirection: preset.direction ?? 135,
  }

  applyingPreset.value = true
  try {
    const params: BackSettingParams | null = preset.id
      ? { type: BackgroundType.GRADIENT, backgroundId: preset.id }
      : null

    if (params) {
      await selectBackground(params)
    } else {
      await updateBacColor({
        colors: setting.bacColorGradient!,
        direction: setting.bacColorDirection,
      })
    }

    userStore.updateBackgroundSetting(setting)
    ElNotification.success({ message: '已应用预设背景' })
  } catch (error: any) {
    ElMessage.error(error.message || '应用预设失败')
  } finally {
    applyingPreset.value = false
  }
}

async function selectPreset(preset: BacGradientVO) {
  emit('update:colors', [...preset.colors])
  emit('update:direction', preset.direction ?? 135)
  await applyPresetBackground(preset)
}

function isPresetActive(preset: BacGradientVO) {
  return (
    props.colors.length === preset.colors.length &&
    props.colors.every((color, index) => color === preset.colors[index]) &&
    props.direction === (preset.direction ?? 135)
  )
}
</script>
