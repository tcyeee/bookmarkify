<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
    <div>
      <div class="mb-2 text-sm font-medium text-slate-700 dark:text-slate-200">渐变背景</div>
      <div class="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-5">
        <button
          v-for="(preset, index) in presets"
          :key="index"
          type="button"
          :style="{ backgroundImage: `linear-gradient(135deg, ${preset.colors.join(', ')})` }"
          :class="[
            'group relative aspect-square overflow-hidden rounded-lg border-2 transition-all shadow-sm',
            isPresetActive(preset)
              ? 'border-blue-500 ring-2 ring-blue-200 ring-offset-2 ring-offset-white dark:ring-offset-slate-900'
              : 'border-transparent hover:-translate-y-0.5 hover:shadow-md dark:hover:border-slate-700',
          ]"
          :disabled="applyingPreset"
          @click="selectPreset(preset)">
          <span
            v-if="preset.isSystem"
            class="icon--memory-lock icon-size-25 absolute left-1 top-1 text-xs leading-none select-none"
            title="系统预设" />
          <div
            v-if="!preset.isSystem"
            class="pointer-events-none absolute inset-x-1 bottom-1 flex items-center justify-center gap-2 rounded-md bg-slate-900/35 px-2 py-1 text-white opacity-0 shadow-sm backdrop-blur group-hover:opacity-100">
            <button
              type="button"
              class="pointer-events-auto inline-flex h-8 w-8 items-center justify-center rounded-md bg-white/85 text-slate-700 shadow transition hover:bg-white disabled:opacity-60 disabled:cursor-not-allowed"
              :disabled="customSaving || applyingPreset"
              title="编辑"
              aria-label="编辑自定义渐变"
              @click.stop="startEditPreset(preset)">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="h-4 w-4">
                <path stroke-linecap="round" stroke-linejoin="round" d="M16.862 4.487 19.5 7.125m-3.375-3.375-9.379 9.379a2.25 2.25 0 0 0-.563.977l-.563 2.25 2.25-.563a2.25 2.25 0 0 0 .977-.563l9.38-9.38m-9.38 9.38 9.38-9.38" />
              </svg>
            </button>
            <button
              type="button"
              class="pointer-events-auto inline-flex h-8 w-8 items-center justify-center rounded-md bg-white/85 text-red-600 shadow transition hover:bg-white disabled:opacity-60 disabled:cursor-not-allowed"
              :disabled="customSaving || applyingPreset"
              title="删除"
              aria-label="删除自定义渐变"
              @click.stop="handleDeletePreset(preset)">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="h-4 w-4">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 7.5h12M9.75 7.5V6.75A1.75 1.75 0 0 1 11.5 5h1a1.75 1.75 0 0 1 1.75 1.75V7.5m-7 0h8.5M9.75 7.5h4.5m-6 0v10a1 1 0 0 0 1 1h5a1 1 0 0 0 1-1v-10m-7 0h8" />
              </svg>
            </button>
          </div>
        </button>
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
import { deleteGradientBackground, selectBackground, updateBacColor, updateGradientBackground } from '@api'
import GradientCustom from './GradientCustom.vue'
import {
  BackgroundType,
  type BacGradientVO,
  type BacSettingVO,
  type BackSettingParams,
  type GradientConfigParams,
} from '@typing'

const userStore = useUserStore()
const sysStore = useSysStore()
const applyingPreset = ref(false)
const customDialogRef = ref<HTMLDialogElement | null>(null)
const customColors = ref<string[]>([])
const customDirection = ref<number>(135)
const customSaving = ref(false)
const editingPreset = ref<GradientPreset | null>(null)
const editingPresetWasActive = ref(false)

type GradientPreset = BacGradientVO & { isSystem?: boolean }

const props = defineProps<{
  colors: string[]
  direction: number
  presets: GradientPreset[]
  saving: boolean
  hasBackground: boolean
}>()

const emit = defineEmits<{
  (e: 'update:colors', value: string[]): void
  (e: 'update:direction', value: number): void
  (e: 'save'): void
  (e: 'reset'): void
}>()

async function applyPresetBackground(preset: GradientPreset) {
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

async function selectPreset(preset: GradientPreset) {
  emit('update:colors', [...preset.colors])
  emit('update:direction', preset.direction ?? 135)
  await applyPresetBackground(preset)
}

function isPresetActive(preset: GradientPreset) {
  return (
    props.colors.length === preset.colors.length &&
    props.colors.every((color, index) => color === preset.colors[index]) &&
    props.direction === (preset.direction ?? 135)
  )
}

function openCustomDialog(presetOrEvent?: GradientPreset | Event) {
  const preset = presetOrEvent && 'colors' in (presetOrEvent as GradientPreset) ? (presetOrEvent as GradientPreset) : undefined
  editingPreset.value = preset ?? null
  editingPresetWasActive.value = preset ? isPresetActive(preset) : false
  customColors.value = [...(preset?.colors ?? props.colors)]
  customDirection.value = preset?.direction ?? props.direction ?? 135
  sysStore.togglePreventKeyEventsFlag(true)
  customDialogRef.value?.showModal()
}

function closeCustomDialog() {
  sysStore.togglePreventKeyEventsFlag(false)
  customDialogRef.value?.close()
  editingPreset.value = null
  editingPresetWasActive.value = false
}

function startEditPreset(preset: GradientPreset) {
  if (preset.isSystem) return
  openCustomDialog(preset)
}

async function handleDeletePreset(preset: GradientPreset) {
  if (preset.isSystem || !preset.id) return
  try {
    await ElMessageBox.confirm('确认删除这个自定义渐变吗？', '提示', { type: 'warning' })
  } catch {
    return
  }

  try {
    await deleteGradientBackground(preset.id)
    await sysStore.refreshSystemConfig()

    if (isPresetActive(preset)) {
      const fallback = sysStore.defaultGradientBackgroundsList?.[0]
      if (fallback) {
        await selectPreset({ ...fallback, isSystem: true })
      }
    }

    ElNotification.success({ message: '已删除自定义渐变' })
  } catch (error: any) {
    ElMessage.error(error.message || '删除失败')
  }
}

async function handleCustomSave() {
  customSaving.value = true
  try {
    if (editingPreset.value?.id) {
      const params: GradientConfigParams = {
        id: editingPreset.value.id,
        colors: [...customColors.value],
        direction: customDirection.value,
      }

      await updateGradientBackground(params)

      if (editingPresetWasActive.value) {
        const setting: BacSettingVO = {
          type: BackgroundType.GRADIENT,
          bacColorGradient: [...customColors.value],
          bacColorDirection: customDirection.value,
        }
        userStore.updateBackgroundSetting(setting)
        emit('update:colors', [...customColors.value])
        emit('update:direction', customDirection.value)
      }

      ElNotification.success({ message: '自定义渐变已更新' })
    } else {
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
    }

    await sysStore.refreshSystemConfig()
    closeCustomDialog()
  } catch (error: any) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    customSaving.value = false
    editingPreset.value = null
    editingPresetWasActive.value = false
  }
}

async function handleCustomReset() {
  // 重用父组件提供的重置逻辑
  emit('reset')
  closeCustomDialog()
}

function handleDialogClose() {
  sysStore.togglePreventKeyEventsFlag(false)
  editingPreset.value = null
  editingPresetWasActive.value = false
}
</script>
