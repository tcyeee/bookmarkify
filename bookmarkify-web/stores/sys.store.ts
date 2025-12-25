import { defaultGradientBackgrounds, defaultImageBackgrounds } from '@api'
import type { BacGradientVO, UserFile } from '@typing'
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSysStore = defineStore(
  'sys',
  () => {
    /* 阻止键盘事件开关 */
    const preventKeyEventsFlag = ref(false)
    /* 键盘事件 */
    const keyEvents = ref<Map<string, Function>>()
    /* 键盘事件上次触发时间(统一节流) */
    const keyEventLastTriggered = ref(0)
    /* 键盘事件触发间隔时间(毫秒) */
    const keyEventInterval = 150
    /* 设置标签页索引 */
    const settingTabIndex = ref(0)
    /* 添加书签对话框可见性 */
    const addBookmarkDialogVisible = ref(false)

    /* 系统默认图片背景 */
    const defaultImageBackgroundsList = ref<UserFile[]>([])
    /* 系统默认渐变背景 */
    const defaultGradientBackgroundsList = ref<BacGradientVO[]>([])

    // 更新系统配置信息
    async function refreshSystemConfig() {
      ;[defaultImageBackgroundsList.value, defaultGradientBackgroundsList.value] = await Promise.all([
        defaultImageBackgrounds(),
        defaultGradientBackgrounds(),
      ])
    }

    /**
     * 触发键盘事件(每次按下任意键自动触发该方法)
     * @param keyCode 按键码
     * @param triggerPath 触发路径
     */
    function triggerKeyEvent(keyCode: string, triggerPath: string) {
      const eventKey = eventName(keyCode, triggerPath)

      if (preventKeyEventsFlag.value) return
      if (!keyEvents.value || !keyEvents.value.has(eventKey)) return

      const now = Date.now()
      if (now - keyEventLastTriggered.value < keyEventInterval) return

      keyEventLastTriggered.value = now
      keyEvents.value.get(eventKey)?.call(null)
    }

    /**
     * 注册键盘事件
     * @param keyCode 按键码
     * @param triggerPath 触发页面限制路径
     * @param triggerFunc 触发函数
     */
    function registerKeyEvent(keyCode: string, triggerPath: string, triggerFunc: Function) {
      const eventKey = eventName(keyCode, triggerPath)

      if (!keyEvents.value) keyEvents.value = new Map()
      keyEvents.value.set(eventKey, triggerFunc)
    }

    function eventName(keyCode: string, triggerPath: string) {
      return keyCode + '-' + triggerPath
    }

    /**
     * 切换阻止键盘事件开关
     * @param value 开关值
     */
    function togglePreventKeyEventsFlag(value: boolean) {
      preventKeyEventsFlag.value = value
    }

    return {
      preventKeyEventsFlag,
      settingTabIndex,
      addBookmarkDialogVisible,
      defaultImageBackgroundsList,
      defaultGradientBackgroundsList,
      triggerKeyEvent,
      registerKeyEvent,
      refreshSystemConfig,
      togglePreventKeyEventsFlag,
      keyEventLastTriggered,
    }
  },
  { persist: false }
)
