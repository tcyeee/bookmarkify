<template>
  <div class="space-y-6 text-slate-900 dark:text-slate-100 transition-colors">
      <section
        class="space-y-4  bg-white/80  transition-colors dark:bg-slate-900/70">
        <div class="flex flex-wrap items-start gap-3">
          <div>
            <h3 class="text-lg font-semibold text-slate-900 dark:text-slate-100">偏好设置</h3>
            <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">
              配置书签打开方式、布局、标题显示和翻页方式，满足你的使用习惯。
            </p>
          </div>
        </div>

      <div
        v-if="preferenceLoading"
        class="flex items-center gap-2 rounded-lg border border-dashed border-slate-200 bg-white/60 px-4 py-3 text-sm text-slate-500 transition-colors dark:border-slate-800 dark:bg-slate-900/50 dark:text-slate-300">
        <span class="icon--loading icon-size-18 animate-spin" />
        <span>正在加载偏好设置...</span>
      </div>

      <div v-else class="grid grid-cols-1 gap-4">
        <div
          class="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white/70 px-4 py-3 transition-colors dark:border-slate-800 dark:bg-slate-900/60">
          <div class="space-y-1">
            <div class="font-semibold">书签打开方式</div>
            <p class="text-sm text-slate-500 dark:text-slate-400">选择在当前标签页或新标签页打开书签。</p>
          </div>
          <select v-model="preferenceForm.bookmarkOpenMode" class="cy-input cy-input-sm w-40">
            <option :value="BookmarkOpenMode.CURRENT_TAB">当前标签页</option>
            <option :value="BookmarkOpenMode.NEW_TAB">新标签页</option>
          </select>
        </div>

        <div
          class="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white/70 px-4 py-3 transition-colors dark:border-slate-800 dark:bg-slate-900/60">
          <div class="space-y-1">
            <div class="font-semibold">书签排列方式</div>
            <p class="text-sm text-slate-500 dark:text-slate-400">根据喜好切换紧凑、默认或宽松排布。</p>
          </div>
          <select v-model="preferenceForm.bookmarkLayout" class="cy-input cy-input-sm w-40">
            <option :value="BookmarkLayoutMode.COMPACT">紧凑</option>
            <option :value="BookmarkLayoutMode.DEFAULT">默认</option>
            <option :value="BookmarkLayoutMode.SPACIOUS">宽松</option>
          </select>
        </div>

        <div
          class="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white/70 px-4 py-3 transition-colors dark:border-slate-800 dark:bg-slate-900/60">
          <div class="space-y-1">
            <div class="font-semibold">翻页方式</div>
            <p class="text-sm text-slate-500 dark:text-slate-400">选择垂直滚动或横向翻页的浏览体验。</p>
          </div>
          <select v-model="preferenceForm.pageMode" class="cy-input cy-input-sm w-40">
            <option :value="PageTurnMode.VERTICAL_SCROLL">垂直滚动</option>
            <option :value="PageTurnMode.HORIZONTAL_PAGE">横向翻页</option>
          </select>
        </div>

        <div
          class="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white/70 px-4 py-3 transition-colors dark:border-slate-800 dark:bg-slate-900/60">
          <div class="space-y-1">
            <div class="font-semibold">极简模式</div>
            <p class="text-sm text-slate-500 dark:text-slate-400">隐藏多余装饰，突出核心信息。</p>
          </div>
          <button
            type="button"
            class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors"
            :class="preferenceForm.minimalMode ? 'bg-primary/80' : 'bg-slate-300 dark:bg-slate-700'"
            @click="toggleBoolean('minimalMode')">
            <span
              class="inline-block h-5 w-5 transform rounded-full bg-white shadow transition"
              :class="preferenceForm.minimalMode ? 'translate-x-5' : 'translate-x-1'" />
          </button>
        </div>

        <div
          class="flex items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white/70 px-4 py-3 transition-colors dark:border-slate-800 dark:bg-slate-900/60">
          <div class="space-y-1">
            <div class="font-semibold">显示标题</div>
            <p class="text-sm text-slate-500 dark:text-slate-400">关闭后仅展示图标，更简洁的书签墙。</p>
          </div>
          <button
            type="button"
            class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors"
            :class="preferenceForm.showTitle ? 'bg-primary/80' : 'bg-slate-300 dark:bg-slate-700'"
            @click="toggleBoolean('showTitle')">
            <span
              class="inline-block h-5 w-5 transform rounded-full bg-white shadow transition"
              :class="preferenceForm.showTitle ? 'translate-x-5' : 'translate-x-1'" />
          </button>
        </div>
      </div>

      <div
        v-if="!preferenceLoading && !preferenceDirty"
        class="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
        <span class="icon--memory-check-circle icon-size-16"></span>
        <span>已同步最新偏好设置</span>
      </div>
    </section>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'
import { queryUserPreference, updateUserPreference } from '@api'
import { BookmarkLayoutMode, BookmarkOpenMode, PageTurnMode, type UserPreference } from '@typing'

const preferenceLoading = ref(false)
const preferenceSaving = ref(false)
const preferenceForm = ref<UserPreference>(createDefaultPreference())
const preferenceOrigin = ref<UserPreference>(createDefaultPreference())
const preferenceLoaded = ref(false)
let autoSaveTimer: ReturnType<typeof setTimeout> | undefined
let pendingSave = false

function createDefaultPreference(): UserPreference {
  return {
    bookmarkOpenMode: BookmarkOpenMode.CURRENT_TAB,
    minimalMode: false,
    bookmarkLayout: BookmarkLayoutMode.DEFAULT,
    showTitle: true,
    pageMode: PageTurnMode.VERTICAL_SCROLL,
    backgroundConfigId: undefined,
    id: undefined,
    uid: undefined,
    updateTime: undefined,
    createTime: undefined,
  }
}

function syncPreference(pref?: UserPreference | null) {
  const merged = { ...createDefaultPreference(), ...(pref ?? {}) }
  preferenceForm.value = merged
  preferenceOrigin.value = JSON.parse(JSON.stringify(merged))
}

async function loadPreference() {
  preferenceLoading.value = true
  try {
    const result = await queryUserPreference()
    syncPreference(result ?? undefined)
  } catch (error: any) {
    ElMessage.error(error?.message || '获取偏好设置失败')
  } finally {
    preferenceLoading.value = false
    preferenceLoaded.value = true
  }
}

async function savePreference() {
  if (!preferenceDirty.value) return
  if (preferenceSaving.value) {
    pendingSave = true
    return
  }
  preferenceSaving.value = true
  try {
    await updateUserPreference(preferenceForm.value)
    preferenceOrigin.value = JSON.parse(JSON.stringify(preferenceForm.value))
  } catch (error: any) {
    ElMessage.error(error?.message || '保存失败，请稍后重试')
  } finally {
    preferenceSaving.value = false
    if (pendingSave) {
      pendingSave = false
      scheduleAutoSave()
    }
  }
}

const preferenceDirty = computed(
  () => JSON.stringify(preferenceForm.value) !== JSON.stringify(preferenceOrigin.value)
)

function toggleBoolean(key: 'minimalMode' | 'showTitle') {
  preferenceForm.value[key] = !preferenceForm.value[key]
}

function scheduleAutoSave() {
  if (!preferenceLoaded.value) return
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer)
  }
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = undefined
    savePreference()
  }, 400)
}

watch(
  preferenceForm,
  () => {
    scheduleAutoSave()
  },
  { deep: true }
)

onMounted(loadPreference)
</script>
