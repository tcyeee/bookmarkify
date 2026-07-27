import { defineStore } from 'pinia'

type ToastType = 'success' | 'error' | 'warning' | 'info'

interface ToastItem {
  id: number
  type: ToastType
  message: string
}

let nextId = 0
const DEFAULT_DURATION = 3000
const ERROR_DURATION = 4000

// 全局轻提示（替代 element-plus 的 ElMessage / ElNotification）
export const useToastStore = defineStore('toast', {
  state: () => ({
    toasts: [] as ToastItem[],
  }),

  actions: {
    push(type: ToastType, message: string, duration = DEFAULT_DURATION) {
      const id = nextId++
      this.toasts.push({ id, type, message })
      // 仅客户端自动移除，避免 SSR 阶段残留定时器
      if (import.meta.client) {
        setTimeout(() => this.remove(id), duration)
      }
    },

    remove(id: number) {
      this.toasts = this.toasts.filter((t) => t.id !== id)
    },

    success(message: string) {
      this.push('success', message)
    },

    error(message: string) {
      this.push('error', message, ERROR_DURATION)
    },

    warning(message: string) {
      this.push('warning', message)
    },

    info(message: string) {
      this.push('info', message)
    },
  },
})
