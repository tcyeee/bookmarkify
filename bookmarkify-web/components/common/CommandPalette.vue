<template>
  <Command.Dialog :visible="visible" theme="custom">
    <template #header>
      <div class="flex items-center gap-2 px-4 pt-4">
        <Command.Input
          class="w-full px-4 py-2 border border-slate-300 rounded-md dark:bg-[#020617] dark:border-[#1f2937] focus:border-[#94a3b8]"
          v-model:value="search"
          placeholder="搜索或输入命令..." />
        <span class="text-xs text-slate-400">⌘K</span>
      </div>
    </template>
    <template #body>
      <Command.List class="pb-1">
        <Command.Empty class="px-4 py-3 text-sm text-slate-400">未找到匹配的命令</Command.Empty>
        <Command.Group heading="常用操作">
          <Command.Item
            v-for="item in items"
            :key="item.value"
            :data-value="item.value"
            @select="handleSelect"
            class="cursor-pointer rounded-md hover:bg-[#f8fafc] dark:hover:bg-gray-800 aria-selected:bg-gray-200 dark:aria-selected:bg-gray-800">
            <div class="flex items-center justify-between px-4 py-2 text-sm">
              <div class="flex flex-col">
                <span>{{ item.label }}</span>
                <span v-if="item.hint" class="mt-0.5 text-xs text-slate-400">{{ item.hint }}</span>
              </div>
              <span v-if="item.kbd" class="ml-3 text-[10px] text-slate-400">{{ item.kbd }}</span>
            </div>
          </Command.Item>
        </Command.Group>
      </Command.List>
    </template>
  </Command.Dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useMagicKeys } from '@vueuse/core'
import { Command } from 'vue-command-palette'

const sysStore = useSysStore()

type PaletteItem = {
  value: string
  label: string
  hint?: string
  kbd?: string
  run: () => void | Promise<void>
}

const visible = ref(false)
const search = ref('')
const userStore = useUserStore()

const items: PaletteItem[] = [
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
    hint: '打开主页',
    run: () => {
      navigate('/')
    },
  },
  {
    value: 'go-setting',
    label: '前往设置',
    hint: '打开设置页面',
    run: () => {
      navigate('/setting')
    },
  },
  {
    value: 'logout',
    label: '退出登陆',
    hint: '退出当前账号',
    run: async () => {
      await userStore.logout()
      close()
    },
  },
]

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
  const target = items.find((item) => item.value === payload.value)
  if (!target) return
  target.run()
}

if (import.meta.client) {
  const keys = useMagicKeys()
  const CmdK = keys['Meta+K']
  const Esc = keys.Escape
  watch(
    () => CmdK?.value,
    (pressed) => {
      if (pressed) {
        if (visible.value) {
          close()
        } else {
          open()
        }
      }
    }
  )
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
