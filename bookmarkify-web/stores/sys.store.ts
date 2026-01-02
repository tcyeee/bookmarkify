import { defaultBackgrounds, myBackgrounds } from '@api'
import type { BacGradientVO, UserFile } from '@typing'
import { defineStore } from 'pinia'

// 系统级别的 Pinia Store（键盘事件、倒计时、系统背景等）
export const useSysStore = defineStore('sys', {
  // 存放系统相关状态
  state: () => ({
    // 是否阻止键盘事件（弹框等场景下关闭全局快捷键）
    preventKeyEventsFlag: false,
    // 已注册的键盘事件映射（keyCode + 路由路径 -> 回调函数）
    keyEvents: null as Map<string, Function> | null,
    // 上一次键盘事件触发时间，用于统一节流
    keyEventLastTriggered: 0,
    // 键盘事件触发的最小时间间隔（毫秒）
    keyEventInterval: 150,
    // 设置页当前选中的标签索引
    settingTabIndex: 0,
    // “添加书签”对话框是否可见
    addBookmarkDialogVisible: false,
    // 短信验证码倒计时秒数
    smsCountdown: 0,
    // 短信验证码倒计时计时器句柄
    smsCountdownTimer: null as ReturnType<typeof setInterval> | null,
    // 邮箱验证码倒计时秒数
    emailCountdown: 0,
    // 邮箱验证码倒计时计时器句柄
    emailCountdownTimer: null as ReturnType<typeof setInterval> | null,
    // 系统内置的图片背景列表
    defaultImageBackgroundsList: [] as UserFile[],
    // 系统内置的渐变背景列表
    defaultGradientBackgroundsList: [] as BacGradientVO[],

    // 用户上传的图片背景列表
    userImageBackgroundsList: [] as UserFile[],
    // 用户上传的渐变背景列表
    userGradientBackgroundsList: [] as BacGradientVO[],
  }),

  // 业务动作（异步/同步方法）
  actions: {
    // 拉取系统默认配置（包括默认背景 + 用户上传背景）
    async refreshSystemConfig() {
      try {
        const [data, mine] = await Promise.all([defaultBackgrounds(), myBackgrounds()])
        this.defaultGradientBackgroundsList = data.gradients ?? []
        this.defaultImageBackgroundsList = data.images ?? []
        this.userGradientBackgroundsList = mine.gradients ?? []
        this.userImageBackgroundsList = mine.images ?? []
      } catch (error) {
        console.error('[SYS] refreshSystemConfig failed', error)
      }
    },

    // 触发键盘事件（在全局 keydown 监听中调用）
    triggerKeyEvent(keyCode: string, triggerPath: string) {
      const eventKey = this.eventName(keyCode, triggerPath)

      // 全局禁止键盘事件时直接返回
      if (this.preventKeyEventsFlag) return
      if (!this.keyEvents || !this.keyEvents.has(eventKey)) return

      const now = Date.now()
      // 使用统一节流间隔，避免连续触发
      if (now - this.keyEventLastTriggered < this.keyEventInterval) return

      this.keyEventLastTriggered = now
      this.keyEvents.get(eventKey)?.call(null)
    },

    // 注册键盘事件，在某个页面/组件挂载时调用
    registerKeyEvent(keyCode: string, triggerPath: string, triggerFunc: Function) {
      const eventKey = this.eventName(keyCode, triggerPath)

      if (!this.keyEvents) this.keyEvents = new Map()
      this.keyEvents.set(eventKey, triggerFunc)
    },

    // 根据按键和页面路径拼接事件 key
    eventName(keyCode: string, triggerPath: string) {
      return keyCode + '-' + triggerPath
    },

    // 切换是否阻止键盘事件
    togglePreventKeyEventsFlag(value: boolean) {
      this.preventKeyEventsFlag = value
    },

    // 启动短信验证码倒计时
    startSmsCountdown(initial = 60) {
      this.smsCountdown = initial
      if (this.smsCountdownTimer !== null) clearInterval(this.smsCountdownTimer)
      this.smsCountdownTimer = setInterval(() => {
        this.smsCountdown--
        if (this.smsCountdown <= 0) {
          this.stopSmsCountdown()
        }
      }, 1000)
    },

    // 停止短信验证码倒计时并清零
    stopSmsCountdown() {
      if (this.smsCountdownTimer !== null) {
        clearInterval(this.smsCountdownTimer)
        this.smsCountdownTimer = null
      }
      this.smsCountdown = 0
    },

    // 启动邮箱验证码倒计时
    startEmailCountdown(initial = 10) {
      this.emailCountdown = initial
      if (this.emailCountdownTimer !== null) clearInterval(this.emailCountdownTimer)
      this.emailCountdownTimer = setInterval(() => {
        this.emailCountdown--
        if (this.emailCountdown <= 0) {
          this.stopEmailCountdown()
        }
      }, 1000)
    },

    // 停止邮箱验证码倒计时并清零
    stopEmailCountdown() {
      if (this.emailCountdownTimer !== null) {
        clearInterval(this.emailCountdownTimer)
        this.emailCountdownTimer = null
      }
      this.emailCountdown = 0
    },
  },
})
