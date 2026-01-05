<template>
  <Command.Dialog :visible="visible" theme="custom">
    <template #header>
      <div class="px-4 pt-4 pb-3">
        <div class="relative">
          <span
            class="icon--memory-search icon-size-25 pointer-events-none absolute inset-y-3 left-3 text-slate-400 dark:text-slate-500"></span>
          <Command.Input
            class="w-full rounded-lg border border-slate-200 bg-white/80 px-11 py-3 text-sm text-slate-900 shadow-sm outline-none transition focus:border-sky-500 focus:ring-2 focus:ring-sky-100 dark:border-slate-700 dark:bg-slate-900/80 dark:text-slate-100 dark:focus:border-sky-500 dark:focus:ring-sky-900/60"
            v-model:value="search"
            placeholder="搜索书签、页面或命令..." />
        </div>
      </div>
    </template>
    <template #body>
      <Command.List class="pb-1">
        <Command.Empty class="px-4 py-4 text-sm text-slate-400 text-center">未找到匹配的命令</Command.Empty>
        <Command.Group v-for="[heading, groupItems] in groupEntries" :key="heading" :heading="heading">
          <Command.Item
            v-for="item in groupItems"
            :key="item.value"
            :data-value="item.value"
            @select="handleSelect"
            class="group mx-1 cursor-pointer rounded-md outline-none transition hover:bg-slate-50 aria-selected:bg-slate-100 focus:bg-slate-100 dark:hover:bg-slate-800 dark:aria-selected:bg-slate-800 dark:focus:bg-slate-800">
            <div class="flex items-center justify-between px-3 py-2.5 text-sm">
              <div class="flex items-center gap-3 text-slate-800 dark:text-slate-100">
                <span
                  v-if="item.iconLeft"
                  :class="[
                    'flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-slate-100 text-slate-400 ring-1 ring-slate-200 transition group-hover:text-sky-500 group-aria-selected:text-sky-500 dark:bg-slate-800 dark:text-slate-500 dark:ring-slate-700',
                    item.iconLeft,
                  ]" />
                <div class="flex flex-col">
                  <span
                    class="font-medium group-aria-selected:text-sky-600 group-hover:text-sky-600 dark:group-aria-selected:text-sky-400 dark:group-hover:text-sky-400">
                    {{ item.label }}
                  </span>
                  <span v-if="item.hint" class="mt-0.5 text-xs text-slate-400 dark:text-slate-500">{{ item.hint }}</span>
                </div>
              </div>
              <div class="ml-3 flex items-center gap-2">
                <span
                  v-if="item.kbd"
                  class="inline-flex items-center gap-1 rounded border border-slate-200 px-1.5 py-0.5 text-[10px] text-slate-400 dark:border-slate-700 dark:text-slate-500">
                  {{ item.kbd }}
                </span>
                <div v-if="item.badges?.length" class="flex flex-wrap justify-end gap-1.5">
                  <button
                    v-for="badge in item.badges"
                    :key="badge.label"
                    type="button"
                    class="inline-flex items-center rounded-md border px-2 py-1 text-[11px] font-medium transition"
                    :class="
                      badge.active
                        ? 'border-sky-500 bg-sky-50 text-sky-700 dark:border-sky-600 dark:bg-sky-900/50 dark:text-sky-100'
                        : 'border-slate-200 text-slate-400 dark:border-slate-700 dark:text-slate-500'
                    "
                    @click.stop.prevent="handleBadgeClick(item.value, badge.value)">
                    {{ badge.label }}
                  </button>
                </div>
                <span
                  v-else-if="item.iconRight || item.submenu"
                  :class="[
                    'text-slate-300 group-hover:text-slate-400 group-aria-selected:text-slate-400 dark:text-slate-600 dark:group-hover:text-slate-400',
                    item.iconRight || 'icon--memory-chevron-right icon-size-16',
                  ]" />
              </div>
            </div>
          </Command.Item>
        </Command.Group>
      </Command.List>
    </template>
  </Command.Dialog>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useMagicKeys, onKeyStroke } from '@vueuse/core'
import { Command } from 'vue-command-palette'
import { BookmarkLayoutMode, BookmarkOpenMode, PageTurnMode, type UserPreference } from '@typing'
import { useAuthStore } from '@stores/auth.store'
import { usePreferenceStore } from '@stores/preference.store'

const sysStore = useSysStore()
const authStore = useAuthStore()
const preferenceStore = usePreferenceStore()

type PaletteItem = {
  value: string
  label: string
  iconLeft?: string
  iconRight?: string
  submenu?: boolean
  hint?: string
  badges?: { label: string; active: boolean; value?: any }[]
  kbd?: string
  run: () => void | Promise<void>
}

const visible = ref(false)
const search = ref('')
const currentMenu = ref<'root' | 'preference'>('root')
const preferenceMenuLoading = ref(false)
const preferenceMenuReady = ref(false)

const rootGroups: Map<string, PaletteItem[]> = new Map()
rootGroups.set('常用操作', [
  {
    value: 'open-add-bookmark',
    label: '添加 / 关联书签',
    hint: '打开添加书签窗口',
    run: () => {
      close()
      sysStore.addBookmarkDialogVisible = true
    },
  },
  {
    value: 'open-preference-menu',
    label: '偏好设置',
    iconLeft: 'icon--memory-toggle-switch-off icon-size-16',
    submenu: true,
    hint: '快速调整书签偏好',
    run: async () => {
      await openPreferenceMenu()
    },
  },
])
rootGroups.set('路由', [
  {
    value: 'go-index',
    label: `前往首页${authStore.authStatus === 'NONE' ? ' (请先登陆)' : ''}`,
    run: () => {
      navigate('/')
    },
  },
  {
    value: 'go-setting',
    label: `前往设置${authStore.authStatus === 'NONE' ? ' (请先登陆)' : ''}`,
    run: () => {
      navigate('/setting')
    },
  },
])
rootGroups.set('其他', [
    {
    value: 'toggle-theme',
    label: '切换主题模式',
    hint: '在浅色和深色模式之间切换',
    run: () => {
      toggleTheme()
      close()
    },
  },
  {
    value: 'logout',
    label: '退出登陆',
    hint: '退出当前账号',
    run: async () => {
      await authStore.logout()
      close()
    },
  },
])

const rootGroupEntries = computed(() => Array.from(rootGroups.entries()))

const currentPreference = computed<UserPreference>(() => ({
  bookmarkOpenMode: BookmarkOpenMode.CURRENT_TAB,
  minimalMode: false,
  bookmarkLayout: BookmarkLayoutMode.DEFAULT,
  showTitle: true,
  pageMode: PageTurnMode.VERTICAL_SCROLL,
  imgBacShow: undefined,
  ...(preferenceStore.preference ?? {}),
}))

const preferenceGroupEntries = computed<[string, PaletteItem[]][]>(() => {
  const pref = currentPreference.value
  return [
    [
      '',
      [
        {
          value: 'back-root',
          iconLeft: 'icon--memory-arrow-left-box icon-size-16',
          label: '返回',
          run: () => {
            backToRoot()
          },
        },
      ],
    ],
    [
      '',
      [
        {
          value: 'pref-open-mode-toggle',
          label: '书签打开方式',
          hint: pref.bookmarkOpenMode === BookmarkOpenMode.CURRENT_TAB ? '当前：当前标签页' : '当前：新标签页',
          badges: [
            { label: '当前标签页', value: BookmarkOpenMode.CURRENT_TAB, active: pref.bookmarkOpenMode === BookmarkOpenMode.CURRENT_TAB },
            { label: '新标签页', value: BookmarkOpenMode.NEW_TAB, active: pref.bookmarkOpenMode === BookmarkOpenMode.NEW_TAB },
          ],
          run: () => toggleOpenMode(),
        },
        {
          value: 'pref-layout-toggle',
          label: '书签排列方式',
          hint:
            pref.bookmarkLayout === BookmarkLayoutMode.COMPACT
              ? '当前：紧凑'
              : pref.bookmarkLayout === BookmarkLayoutMode.SPACIOUS
                ? '当前：宽松'
                : '当前：默认',
          badges: [
            { label: '紧凑', value: BookmarkLayoutMode.COMPACT, active: pref.bookmarkLayout === BookmarkLayoutMode.COMPACT },
            { label: '默认', value: BookmarkLayoutMode.DEFAULT, active: pref.bookmarkLayout === BookmarkLayoutMode.DEFAULT },
            { label: '宽松', value: BookmarkLayoutMode.SPACIOUS, active: pref.bookmarkLayout === BookmarkLayoutMode.SPACIOUS },
          ],
          run: () => toggleLayoutMode(),
        },
        {
          value: 'pref-page-mode-toggle',
          label: '翻页方式',
          hint: pref.pageMode === PageTurnMode.VERTICAL_SCROLL ? '当前：垂直滚动' : '当前：横向翻页',
          badges: [
            { label: '垂直滚动', value: PageTurnMode.VERTICAL_SCROLL, active: pref.pageMode === PageTurnMode.VERTICAL_SCROLL },
            { label: '横向翻页', value: PageTurnMode.HORIZONTAL_PAGE, active: pref.pageMode === PageTurnMode.HORIZONTAL_PAGE },
          ],
          run: () => togglePageMode(),
        },
        {
          value: 'pref-minimal-toggle',
          label: pref.minimalMode ? '关闭极简模式' : '开启极简模式',
          hint: pref.minimalMode ? '当前已开启' : '当前已关闭',
          iconRight: pref.minimalMode ? 'icon--memory-toggle-switch-off icon-size-30' : 'icon--memory-toggle-switch-on icon-size-30',
          run: () =>
            updatePreference({
              minimalMode: !pref.minimalMode,
            }),
        },
        {
          value: 'pref-show-title-toggle',
          label: pref.showTitle ? '关闭标题显示' : '开启标题显示',
          hint: pref.showTitle ? '当前已关闭' : '当前已开启',
          iconRight: pref.showTitle ? 'icon--memory-toggle-switch-off icon-size-30' : 'icon--memory-toggle-switch-on icon-size-30',
          run: () =>
            updatePreference({
              showTitle: !pref.showTitle,
            }),
        },
      ],
    ],
  ]
})

const groupEntries = computed(() =>
  currentMenu.value === 'root' ? rootGroupEntries.value : preferenceGroupEntries.value
)
const allItems = computed(() => groupEntries.value.flatMap(([, list]) => list))

async function ensurePreferenceLoaded() {
  if (preferenceMenuReady.value || preferenceMenuLoading.value) return
  if (preferenceStore.preference !== undefined) {
    preferenceMenuReady.value = true
    return
  }
  preferenceMenuLoading.value = true
  try {
    await preferenceStore.fetchPreference()
    preferenceMenuReady.value = true
  } catch (error) {
    console.error('[CommandPalette] load preference failed', error)
  } finally {
    preferenceMenuLoading.value = false
  }
}

async function updatePreference(patch: Partial<UserPreference>) {
  await ensurePreferenceLoaded()
  if (!preferenceMenuReady.value && preferenceStore.preference === undefined) return
  const merged: UserPreference = { ...currentPreference.value, ...patch }
  try {
    await preferenceStore.savePreference(merged)
  } catch (error) {
    console.error('[CommandPalette] save preference failed', error)
  }
}

function toggleOpenMode() {
  const next =
    currentPreference.value.bookmarkOpenMode === BookmarkOpenMode.CURRENT_TAB
      ? BookmarkOpenMode.NEW_TAB
      : BookmarkOpenMode.CURRENT_TAB
  void updatePreference({ bookmarkOpenMode: next })
}

function toggleLayoutMode() {
  const order = [BookmarkLayoutMode.COMPACT, BookmarkLayoutMode.DEFAULT, BookmarkLayoutMode.SPACIOUS]
  const current = currentPreference.value.bookmarkLayout
  const idx = order.indexOf(current)
  const next = order[(idx + 1) % order.length]
  void updatePreference({ bookmarkLayout: next })
}

function togglePageMode() {
  const next =
    currentPreference.value.pageMode === PageTurnMode.VERTICAL_SCROLL
      ? PageTurnMode.HORIZONTAL_PAGE
      : PageTurnMode.VERTICAL_SCROLL
  void updatePreference({ pageMode: next })
}

function handleBadgeClick(itemValue: string, badgeValue: any) {
  if (badgeValue === undefined) return
  switch (itemValue) {
    case 'pref-open-mode-toggle':
      void updatePreference({ bookmarkOpenMode: badgeValue as BookmarkOpenMode })
      break
    case 'pref-layout-toggle':
      void updatePreference({ bookmarkLayout: badgeValue as BookmarkLayoutMode })
      break
    case 'pref-page-mode-toggle':
      void updatePreference({ pageMode: badgeValue as PageTurnMode })
      break
    default:
      break
  }
}

function open() {
  visible.value = true
  sysStore.togglePreventKeyEventsFlag(true)
}

function close() {
  visible.value = false
  search.value = ''
  currentMenu.value = 'root'
  sysStore.togglePreventKeyEventsFlag(false)
}

function toggleTheme() {
  if (!import.meta.client) return
  const body = document.body
  const isDark = body.classList.contains('dark') || body.dataset.theme === 'dark'
  if (isDark) {
    body.classList.remove('dark')
    body.dataset.theme = 'light'
  } else {
    body.classList.add('dark')
    body.dataset.theme = 'dark'
  }
}

function navigate(path: string) {
  close()
  navigateTo(path)
}

async function openPreferenceMenu() {
  currentMenu.value = 'preference'
  search.value = ''
  await ensurePreferenceLoaded()
}

function backToRoot() {
  currentMenu.value = 'root'
  search.value = ''
}

function handleSelect(payload: { value: string }) {
  const target = allItems.value.find((item) => item.value === payload.value)
  if (!target) return
  target.run()
}

if (import.meta.client) {
  const keys = useMagicKeys()
  const Esc = keys.Escape

  onKeyStroke('k', (e) => {
    if (!e.metaKey) return
    if (visible.value) {
      close()
    } else {
      open()
    }
  })

  watch(
    () => Esc?.value,
    (pressed) => {
      if (pressed && visible.value) {
        close()
      }
    }
  )
}
</script>
