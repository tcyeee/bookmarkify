import { defineStore } from 'pinia'
import { ref } from 'vue'
import { DialogStatus, type KeyEvent } from './types'
import type { BackgroundGradientEntity } from '@typing'

export const useSysStore = defineStore(
  'sys',
  () => {
    // state
    const preventKeyEventsFlag = ref(false)
    const sysEvents = ref<Map<string, Map<string, KeyEvent>> | undefined>(undefined)
    const dialogLoginManage = ref<Set<DialogStatus> | undefined>(undefined)
    const settingTabIndex = ref(0)
    const addBookmarkDialogVisible = ref(false)

    // 默认图片背景
    const defaultImageBackground = ref<BackgroundGradientEntity[]>([])
    // 默认渐变色背景
    const defaultGradientBackground = ref<string | undefined>(undefined)

    // actions

    // 获取当前 dialog 状态
    function checkDialogStatus(dialogName: DialogStatus | string): boolean {
      const status = DialogStatus[dialogName as keyof typeof DialogStatus]
      return dialogLoginManage.value ? dialogLoginManage.value.has(status) : false
    }

    // 修改当前 dialog 状态
    function updateDialogStatus(dialogName: DialogStatus, status?: boolean): void {
      if (!dialogLoginManage.value) throw new Error('dialogLoginManage 未定义')
      if (status) {
        dialogLoginManage.value.add(dialogName)
      } else {
        dialogLoginManage.value.delete(dialogName)
      }
    }

    // 触发键盘事件
    function triggerKeyEvent(keyCode: string, path: string) {
      if (preventKeyEventsFlag.value) return
      if (!sysEvents.value || !sysEvents.value.has(keyCode)) return

      console.log(`[DEBUG]: 触发按键: ${keyCode}`)
      const events = sysEvents.value.get(keyCode) || new Map()
      if (!events.has(keyCode + path)) return

      const info = events.get(keyCode + path)
      info?.triggerFunc.call(null)
    }

    // 获取按键关联键盘事件
    function getKeyEvent(keyCode: string): Map<string, KeyEvent> | undefined {
      if (!(sysEvents.value instanceof Map)) sysEvents.value = new Map()
      return sysEvents.value.get(keyCode)
    }

    // 存入键盘事件
    function setKeyEvent(keyCode: string, events: Map<string, KeyEvent>) {
      if (!sysEvents.value) sysEvents.value = new Map()
      sysEvents.value.set(keyCode, events)
      console.log(`为${keyCode}绑定一个键盘事件,当前总事件:${sysEvents.value.size}`)
    }

    // 注册键盘事件
    function registerKeyEvent(keyCode: string, currentPath: string, triggerFunc: Function) {
      const events = getKeyEvent(keyCode) || new Map()
      events.set(keyCode + currentPath, { currentPath, triggerFunc })
      setKeyEvent(keyCode, events)
    }

    return {
      // state
      preventKeyEventsFlag,
      sysEvents,
      dialogLoginManage,
      settingTabIndex,
      addBookmarkDialogVisible,
      // actions
      checkDialogStatus,
      updateDialogStatus,
      triggerKeyEvent,
      getKeyEvent,
      setKeyEvent,
      registerKeyEvent,
    }
  },
  { persist: false }
)
