import { defineStore } from 'pinia'

/**
 * 「移动到…」的目标文件夹选择器。
 *
 * 形态刻意与 confirm.store 一致（`pick()` 返回 Promise，取消即 reject），
 * 这样调用处写出来的就是同一个 `try { await ... } catch { return }` 结构。
 *
 * 存在的理由：拖拽是 HTML5 drag-and-drop，触屏根本不会触发（实测触摸拖动全程 0 个 drag 事件），
 * 所以手机上原本没有任何办法把书签换个文件夹。键盘用户同样够不着拖拽。
 */
export const useMovePickerStore = defineStore('movePicker', {
  state: () => ({
    visible: false,
    /** 正在被移动的节点 id，选择器据此排除「移到自己所在的文件夹」等无效项 */
    nodeId: '' as string,
    title: '移动到',
    // 当前弹窗的 resolve/reject，仅在弹窗展示期间有效
    resolveFn: null as ((parentKey: string) => void) | null,
    rejectFn: null as (() => void) | null,
  }),

  actions: {
    /** 选中目标后 resolve 目标父级 key（根目录为 ROOT_KEY），取消/关闭则 reject */
    pick(nodeId: string): Promise<string> {
      this.nodeId = nodeId
      this.visible = true
      return new Promise((resolve, reject) => {
        this.resolveFn = resolve
        this.rejectFn = reject
      })
    },

    accept(parentKey: string) {
      this.visible = false
      this.resolveFn?.(parentKey)
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
