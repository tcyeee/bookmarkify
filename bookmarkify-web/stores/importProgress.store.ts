import { defineStore } from 'pinia'

// 书签导入进度：跨页面常驻的右上角通知依赖此 store 驱动
export const useImportProgressStore = defineStore('importProgress', {
  persist: false,

  state: () => ({
    active: false,
    total: 0,
    resolved: 0,
    pendingIds: {} as Record<string, true>,
    autoHideTimer: undefined as ReturnType<typeof setTimeout> | undefined,
  }),

  getters: {
    done(state): boolean {
      return state.total > 0 && state.resolved >= state.total
    },
  },

  actions: {
    // 新一批导入的 LOADING 节点 id；若已有进行中的批次则并入而非覆盖
    startBatch(nodeIds: string[]) {
      if (nodeIds.length === 0) return
      if (this.autoHideTimer) {
        clearTimeout(this.autoHideTimer)
        this.autoHideTimer = undefined
      }
      for (const id of nodeIds) this.pendingIds[id] = true
      this.total += nodeIds.length
      this.active = true
    },

    // WebSocket 收到某节点解析完成
    markResolved(nodeId: string) {
      if (!this.pendingIds[nodeId]) return
      delete this.pendingIds[nodeId]
      this.resolved++
      if (this.done) {
        this.autoHideTimer = setTimeout(() => this.dismiss(), 4000)
      }
    },

    dismiss() {
      if (this.autoHideTimer) {
        clearTimeout(this.autoHideTimer)
        this.autoHideTimer = undefined
      }
      this.active = false
      this.total = 0
      this.resolved = 0
      this.pendingIds = {}
    },
  },
})
