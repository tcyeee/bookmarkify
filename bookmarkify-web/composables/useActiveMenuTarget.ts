import { ref } from 'vue'

/**
 * 「当前哪一行/哪张磁贴的菜单是打开的」——全局唯一一份，供高亮背景用，不按组件实例各存一份。
 *
 * 根因：@imengyu/vue3-context-menu 内部用 Vue 的 render(vnode, container) 把每次
 * showContextMenu() 都渲染进同一个单例容器；上一份菜单的关闭动画还没走完、第二次调用就已经
 * 发生时，Vue 会原地 patch 同一个组件实例而不是先卸载再挂载，于是这个实例的 onClose props
 * 被新一份直接覆盖——旧菜单再也没有任何时机把它的 onClose emit 出去，先长按 A 再长按 B，
 * A 的 onClose 永远不会被调用。
 *
 * 若各行各自存一份本地的 contextMenuNodeId，就必须依赖"我自己的 onClose 一定会被调用"这件
 * 事，而上面的库行为并不保证。改成全局共享一个 id：不管谁打开新菜单，都会把这个 id 指向新
 * 目标，旧目标的高亮随之立即失焦，不再依赖任何可能永远不触发的旧回调；真正的 onClose（只有
 * 最后一次打开的那份能可靠触发）负责在菜单彻底关闭时把它清空。
 */
const activeMenuTarget = ref<string | null>(null)

export function useActiveMenuTarget() {
  return activeMenuTarget
}
