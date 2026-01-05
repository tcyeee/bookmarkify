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
              <div class="flex flex-col text-slate-800 dark:text-slate-100">
                <span
                  class="font-medium group-aria-selected:text-sky-600 group-hover:text-sky-600 dark:group-aria-selected:text-sky-400 dark:group-hover:text-sky-400">
                  {{ item.label }}
                </span>
                <span v-if="item.hint" class="mt-0.5 text-xs text-slate-400 dark:text-slate-500">{{ item.hint }}</span>
              </div>
              <span
                v-if="item.kbd"
                class="ml-3 inline-flex items-center gap-1 rounded border border-slate-200 px-1.5 py-0.5 text-[10px] text-slate-400 dark:border-slate-700 dark:text-slate-500">
                {{ item.kbd }}
              </span>
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
import { useAuthStore } from '@stores/auth.store'

const sysStore = useSysStore()
const authStore = useAuthStore()

type PaletteItem = {
  value: string
  label: string
  hint?: string
  kbd?: string
  run: () => void | Promise<void>
}

const visible = ref(false)
const search = ref('')

const groups: Map<string, PaletteItem[]> = new Map()
groups.set('常用操作', [
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
    value: 'go-index',
    label: '前往首页',
    hint: `打开主页${authStore.authStatus === 'NONE' ? ' (请先登陆)' : ''}`,
    run: () => {
      navigate('/')
    },
  },
  {
    value: 'go-setting',
    label: '前往设置',
    hint: `打开设置页面${authStore.authStatus === 'NONE' ? ' (请先登陆)' : ''}`,
    run: () => {
      navigate('/setting')
    },
  },
  {
    value: 'open-add-bookmark',
    label: '添加 / 关联书签',
    hint: '打开添加书签窗口',
    run: () => {
      close()
      sysStore.addBookmarkDialogVisible = true
    },
  },
])
groups.set('账号操作', [
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

const groupEntries = computed(() => Array.from(groups.entries()))
const allItems = computed(() => groupEntries.value.flatMap(([, list]) => list))

function open() {
  visible.value = true
  sysStore.togglePreventKeyEventsFlag(true)
}

function close() {
  visible.value = false
  search.value = ''
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
