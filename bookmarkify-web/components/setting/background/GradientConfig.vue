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
        <button
          type="button"
          class="aspect-square rounded-lg border-2 transition-all shadow-sm border-dashed border-slate-200 hover:-translate-y-0.5 hover:shadow-md dark:border-slate-700"
          @click="openCustomDialog">
          <div class="h-full w-full flex items-center justify-center text-slate-500 dark:text-slate-300 text-xl font-semibold">＋</div>
        </button>
      </div>
    </div>

    <dialog ref="customDialogRef" class="cy-modal" @close="handleDialogClose">
      <div class="cy-modal-box max-w-3xl">
        <h3 class="text-lg font-semibold text-slate-900 dark:text-slate-100 mb-4">自定义渐变</h3>
        <GradientCustom
          :colors="customColors"
          :direction="customDirection"
          :saving="customSaving"
          :has-background="hasBackground"
          @update:colors="(v) => (customColors = v)"
          @update:direction="(v) => (customDirection = v)"
          @save="handleCustomSave"
          @reset="handleCustomReset" />
        <div class="cy-modal-action">
          <button class="cy-btn cy-btn-ghost" type="button" @click="closeCustomDialog" :disabled="customSaving">取消</button>
          <button class="cy-btn cy-btn-primary" type="button" @click="handleCustomSave" :disabled="customSaving">保存</button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button>close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { selectBackground, updateBacColor } from '@api'
import GradientCustom from './GradientCustom.vue'
import { BackgroundType, type BacSettingVO, type BackSettingParams, type BacGradientVO } from '@typing'

const userStore = useUserStore()
const sysStore = useSysStore()
const applyingPreset = ref(false)
const customDialogRef = ref<HTMLDialogElement | null>(null)
const customColors = ref<string[]>([])
const customDirection = ref<number>(135)
const customSaving = ref(false)

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

function openCustomDialog() {
  customColors.value = [...props.colors]
  customDirection.value = props.direction ?? 135
  sysStore.togglePreventKeyEventsFlag(true)
  customDialogRef.value?.showModal()
}

function closeCustomDialog() {
  sysStore.togglePreventKeyEventsFlag(false)
  customDialogRef.value?.close()
}

async function handleCustomSave() {
  customSaving.value = true
  try {
    await updateBacColor({
      colors: customColors.value,
      direction: customDirection.value,
    })
    const setting: BacSettingVO = {
      type: BackgroundType.GRADIENT,
      bacColorGradient: [...customColors.value],
      bacColorDirection: customDirection.value,
    }
    userStore.updateBackgroundSetting(setting)
    emit('update:colors', [...customColors.value])
    emit('update:direction', customDirection.value)
    ElNotification.success({ message: '自定义渐变已保存' })
    closeCustomDialog()
  } catch (error: any) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    customSaving.value = false
  }
}

async function handleCustomReset() {
  // 重用父组件提供的重置逻辑
  emit('reset')
  closeCustomDialog()
}

function handleDialogClose() {
  sysStore.togglePreventKeyEventsFlag(false)
}
</script>
