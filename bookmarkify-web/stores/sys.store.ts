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
    // “添加书签”对话框的目标文件夹节点 id，null 表示落到根目录。
    // 由文件夹卡片菜单的「添加书签」写入，AddOneDialog 读取后把新书签直接放进该文件夹；
    // 对话框关闭时由 AddOneDialog 清空，因此其它入口（工具栏、命令面板）不必关心它。
    addBookmarkTargetDirId: null as string | null,
    // 邮箱验证码倒计时秒数
    emailCountdown: 0,
    // 邮箱验证码倒计时计时器句柄
    emailCountdownTimer: null as ReturnType<typeof setInterval> | null,
  }),

  // 业务动作（异步/同步方法）
  actions: {
    // 打开“添加书签”对话框。dirNodeId 非空时，这次添加的书签会直接落进该文件夹。
    // 必须走这个入口而不是直接改 addBookmarkDialogVisible：目标文件夹是一次性的，
    // 忘记重置就会让下一次「工具栏新增」把书签放进上一回选中的那个文件夹。
    openAddBookmarkDialog(dirNodeId: string | null = null) {
      this.addBookmarkTargetDirId = dirNodeId
      this.addBookmarkDialogVisible = true
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
