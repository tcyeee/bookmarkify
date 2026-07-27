<template>
  <div class="max-w-3xl space-y-5 text-slate-900 transition-colors dark:text-slate-100">
    <section
      class="bg-white/70  backdrop-blur dark:border-slate-800/70 dark:bg-slate-900/70">
          <div>
            <h3 class="text-lg font-semibold">{{ $t('preferenceSettings.title') }}</h3>
          </div>

      <div
        v-if="preferenceLoading"
        class="mt-4 flex items-center gap-2 rounded-xl border border-dashed border-slate-200/80 bg-white/70 px-4 py-3 text-sm text-slate-500 transition-colors dark:border-slate-800/70 dark:bg-slate-900/60 dark:text-slate-300">
        <Icon icon="mdi:loading" class="size-[18px] animate-spin" />
        <span>{{ $t('preferenceSettings.loading') }}</span>
      </div>

      <div v-else class="mt-2 divide-y divide-slate-200/70 dark:divide-slate-800/70">
        <div class="flex flex-wrap items-start gap-3 py-4">
          <div class="flex-1 space-y-1 min-w-[220px]">
            <div class="text-sm font-semibold">{{ $t('preferenceSettings.bookmarkOpenMode.label') }}</div>
            <p class="text-xs text-slate-500 dark:text-slate-400">{{ $t('preferenceSettings.bookmarkOpenMode.desc') }}</p>
          </div>
          <select v-model="preferenceForm.bookmarkOpenMode" class="cy-input cy-input-sm w-44">
            <option :value="BookmarkOpenMode.CURRENT_TAB">{{ $t('preferenceSettings.bookmarkOpenMode.currentTab') }}</option>
            <option :value="BookmarkOpenMode.NEW_TAB">{{ $t('preferenceSettings.bookmarkOpenMode.newTab') }}</option>
          </select>
        </div>

        <div class="flex flex-wrap items-start gap-3 py-4">
          <div class="flex-1 space-y-1 min-w-[220px]">
            <div class="text-sm font-semibold">{{ $t('preferenceSettings.bookmarkGap.label') }}</div>
            <p class="text-xs text-slate-500 dark:text-slate-400">{{ $t('preferenceSettings.bookmarkGap.desc') }}</p>
          </div>
          <select v-model="preferenceForm.bookmarkGap" class="cy-input cy-input-sm w-44">
            <option :value="BookmarkGapMode.COMPACT">{{ $t('preferenceSettings.bookmarkGap.compact') }}</option>
            <option :value="BookmarkGapMode.DEFAULT">{{ $t('preferenceSettings.bookmarkGap.default') }}</option>
            <option :value="BookmarkGapMode.SPACIOUS">{{ $t('preferenceSettings.bookmarkGap.spacious') }}</option>
          </select>
        </div>

        <div class="flex flex-wrap items-start gap-3 py-4">
          <div class="flex-1 space-y-1 min-w-[220px]">
            <div class="text-sm font-semibold">{{ $t('preferenceSettings.bookmarkImageSize.label') }}</div>
            <p class="text-xs text-slate-500 dark:text-slate-400">{{ $t('preferenceSettings.bookmarkImageSize.desc') }}</p>
          </div>
          <select v-model="preferenceForm.bookmarkImageSize" class="cy-input cy-input-sm w-44">
            <option :value="BookmarkImageSize.LARGE">{{ $t('preferenceSettings.bookmarkImageSize.large') }}</option>
            <option :value="BookmarkImageSize.MEDIUM">{{ $t('preferenceSettings.bookmarkImageSize.medium') }}</option>
            <option :value="BookmarkImageSize.SMALL">{{ $t('preferenceSettings.bookmarkImageSize.small') }}</option>
          </select>
        </div>

        <div class="flex flex-wrap items-start gap-3 py-4">
          <div class="flex-1 space-y-1 min-w-[220px]">
            <div class="text-sm font-semibold">{{ $t('preferenceSettings.showTitle.label') }}</div>
            <p class="text-xs text-slate-500 dark:text-slate-400">{{ $t('preferenceSettings.showTitle.desc') }}</p>
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

        <div class="flex flex-wrap items-start gap-3 py-4">
          <div class="flex-1 space-y-1 min-w-[220px]">
            <div class="text-sm font-semibold">{{ $t('preferenceSettings.showDesktopAddEntry.label') }}</div>
            <p class="text-xs text-slate-500 dark:text-slate-400">{{ $t('preferenceSettings.showDesktopAddEntry.desc') }}</p>
          </div>
          <button
            type="button"
            class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors"
            :class="preferenceForm.showDesktopAddEntry ? 'bg-primary/80' : 'bg-slate-300 dark:bg-slate-700'"
            @click="toggleBoolean('showDesktopAddEntry')">
            <span
              class="inline-block h-5 w-5 transform rounded-full bg-white shadow transition"
              :class="preferenceForm.showDesktopAddEntry ? 'translate-x-5' : 'translate-x-1'" />
          </button>
        </div>

        <div class="flex flex-wrap items-start gap-3 py-4">
          <div class="flex-1 space-y-1 min-w-[220px]">
            <div class="text-sm font-semibold">{{ $t('preferenceSettings.language.label') }}</div>
            <p class="text-xs text-slate-500 dark:text-slate-400">{{ $t('preferenceSettings.language.desc') }}</p>
          </div>
          <select v-model="selectedLocale" class="cy-input cy-input-sm w-44" @change="onLocaleChange">
            <option v-for="l in availableLocales" :key="l.code" :value="l.code">{{ l.name }}</option>
          </select>
        </div>
      </div>
    </section>

    <div
      v-if="!preferenceLoading && !preferenceDirty"
      class="flex items-center gap-2 text-xs text-slate-500 transition-colors dark:text-slate-400">
      <Icon icon="mdi:cloud" class="size-4" />
      <span>{{ $t('preferenceSettings.synced') }}</span>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch, type ComputedRef, type Ref } from 'vue'
import { storeToRefs } from 'pinia'
import {
  BookmarkImageSize,
  BookmarkGapMode,
  BookmarkOpenMode,
  PageTurnMode,
  type UserPreference,
} from '@typing'
import { usePreferenceStore } from '@stores/preference.store'

const preferenceStore = usePreferenceStore()
const { preference } = storeToRefs(preferenceStore)

// @nuxtjs/i18n 通过 declare module 'vue-i18n' 扩展 Composer 添加 locales/setLocale，
// 但该模块增强在本项目的 vue-tsc 全局类型解析下未生效，故此处显式声明扩展后的类型。
type I18nComposerExt = {
  locale: Ref<string>
  locales: ComputedRef<Array<{ code: string; name?: string }>>
  setLocale: (code: string) => Promise<void>
}
const { locale, locales, setLocale } = useI18n() as unknown as I18nComposerExt
const availableLocales = computed(() => locales.value)
const selectedLocale = ref(locale.value)

function onLocaleChange() {
  setLocale(selectedLocale.value)
}

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
    bookmarkGap: BookmarkGapMode.DEFAULT,
    bookmarkImageSize: BookmarkImageSize.MEDIUM,
    showTitle: true,
    showDesktopAddEntry: true,
    pageMode: PageTurnMode.VERTICAL_SCROLL,
    imgBacShow: undefined,
  }
}

function snapshotPreference(pref: UserPreference): UserPreference {
  return JSON.parse(JSON.stringify(pref))
}

function syncPreference(pref?: UserPreference | null) {
  const merged = { ...createDefaultPreference(), ...(pref ?? {}) }
  preferenceForm.value = merged
  preferenceOrigin.value = snapshotPreference(merged)
}

async function loadPreference() {
  if (preferenceLoaded.value) return

  // store 中已有数据时直接同步，无需再走 loading 状态
  if (preference.value) {
    syncPreference(preference.value)
    preferenceLoaded.value = true
    return
  }

  preferenceLoading.value = true
  try {
    await preferenceStore.fetchPreference()
    syncPreference(preference.value ?? undefined)
  } catch {
    // 错误已由 http 层统一提示
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
    await preferenceStore.savePreference(preferenceForm.value)
    preferenceOrigin.value = snapshotPreference(preferenceForm.value)
  } catch {
    // 错误已由 http 层统一提示
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

function toggleBoolean(key: 'minimalMode' | 'showTitle' | 'showDesktopAddEntry') {
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

watch(locale, (val) => {
  selectedLocale.value = val
})

onMounted(loadPreference)
</script>
