import { defineStore } from 'pinia'

type ConfirmType = 'warning' | 'info' | 'error'

interface ConfirmOptions {
  title?: string
  confirmText?: string
  cancelText?: string
  type?: ConfirmType
}

// 全局确认对话框（替代 element-plus 的 ElMessageBox.confirm）
export const useConfirmStore = defineStore('confirm', {
  state: () => ({
    visible: false,
    message: '',
    title: '提示',
    confirmText: '确定',
    cancelText: '取消',
    type: 'warning' as ConfirmType,
    // 当前弹窗的 resolve/reject，仅在弹窗展示期间有效
    resolveFn: null as (() => void) | null,
    rejectFn: null as (() => void) | null,
  }),

  actions: {
    // 弹出确认框；点击确认 resolve，取消/关闭(Esc、点击遮罩)则 reject
    confirm(message: string, options: ConfirmOptions = {}): Promise<void> {
      this.message = message
      this.title = options.title ?? '提示'
      this.confirmText = options.confirmText ?? '确定'
      this.cancelText = options.cancelText ?? '取消'
      this.type = options.type ?? 'warning'
      this.visible = true
      return new Promise((resolve, reject) => {
        this.resolveFn = resolve
        this.rejectFn = reject
      })
    },

    accept() {
      this.visible = false
      this.resolveFn?.()
      this.resolveFn = null
      this.rejectFn = null
    },

    dismiss() {
      this.visible = false
      this.rejectFn?.()
      this.resolveFn = null
      this.rejectFn = null
    },
  },
})
