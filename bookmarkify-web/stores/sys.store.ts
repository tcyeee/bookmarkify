import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSysStore = defineStore(
  'sys',
  () => {
    /* 阻止键盘事件开关 */
    const preventKeyEventsFlag = ref(false)
    /* 键盘事件 */
    const keyEvents = ref<Map<string, Set<Function>>>()
    /* 设置标签页索引 */
    const settingTabIndex = ref(0)
    /* 添加书签对话框可见性 */
    const addBookmarkDialogVisible = ref(false)

    /**
     * 触发键盘事件(每次按下任意键自动触发该方法)
     * @param keyCode 按键码
     * @param triggerPath 触发路径
     */
    function triggerKeyEvent(keyCode: string, path: string) {
      const eventName = keyCode + path

      if (preventKeyEventsFlag.value) return
      if (!keyEvents.value || !keyEvents.value.has(eventName)) return

      console.log(`[KEYBOARD]: 触发'${keyCode}'键事件`)
      keyEvents.value.get(eventName)?.forEach((event) => event.call(null))
    }

    /**
     * 注册键盘事件
     * @param keyCode 按键码
     * @param triggerPath 触发页面限制路径
     * @param triggerFunc 触发函数
     */
    function registerKeyEvent(keyCode: string, triggerPath: string, triggerFunc: Function) {
      const eventKey = keyCode + triggerPath

      if (!keyEvents.value) keyEvents.value = new Map()
      if (!keyEvents.value.has(eventKey)) keyEvents.value.set(eventKey, new Set())

      keyEvents.value.get(eventKey)!.add(triggerFunc)
      console.log(`[KEYBOARD] 在${triggerPath}页面为'${keyCode}'键绑定事件,当前总事件数:${keyEvents.value.size}`)
    }

    return {
      preventKeyEventsFlag,
      settingTabIndex,
      addBookmarkDialogVisible,
      triggerKeyEvent,
      registerKeyEvent,
    }
  },
  { persist: false }
)
