import { HomeItemType, ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import { bookmarksMoveNode, bookmarksSort } from '@api'
import { useMovePickerStore } from '@stores/movePicker.store'

/**
 * 「移动到…」「上移 / 下移」—— 拖拽之外的第二条排序/归档路径。
 *
 * 拖拽走的是 HTML5 drag-and-drop（@atlaskit/pragmatic-drag-and-drop 的 element adapter 会设
 * `draggable` 属性并监听 dragstart），而移动浏览器不会从触摸发起原生拖放 —— 实测在书签行上做完整的
 * touchstart→touchmove→touchend，drag 事件触发数为 0。也就是说手机上原本没有任何办法整理书签。
 * 键盘用户同样够不着拖拽，所以这条路径不只是「移动端的补丁」。
 *
 * 落库调用与 BookmarkFolderCard 的拖拽落点保持一致（reorderLocal/moveLocal + bookmarksSort +
 * bookmarksMoveNode），集中在这里是为了两条路径不会各自演化。
 */
export function useBookmarkMove() {
  const bookmarkStore = useBookmarkStore()

  /** 本地顺序变更后统一落库。失败只记日志：下一次布局拉取会以服务端为准纠正 */
  function persistOrder(scene: string) {
    bookmarksSort(bookmarkStore.fullOrderParams).catch((error) =>
      console.error(`[useBookmarkMove] 排序保存失败(${scene})`, error),
    )
  }

  /**
   * 在当前文件夹内上移/下移一位。
   * delta = -1 上移，+1 下移。已经在端点时静默不动（菜单项那时也是禁用的）。
   */
  function moveWithin(node: UserLayoutNodeVO, delta: -1 | 1) {
    const parentKey = bookmarkStore.parentKeyOf(node.id)
    if (!parentKey) return
    const ids = [...(bookmarkStore.order[parentKey] ?? [])]
    const from = ids.indexOf(node.id)
    const to = from + delta
    if (from < 0 || to < 0 || to >= ids.length) return
    ids.splice(to, 0, ...ids.splice(from, 1))
    bookmarkStore.reorderLocal(parentKey, ids)
    persistOrder('reorder')
  }

  /** 该节点在所属文件夹里是否已经在首/末位，用于禁用对应菜单项 */
  function positionOf(node: UserLayoutNodeVO) {
    const parentKey = bookmarkStore.parentKeyOf(node.id)
    const ids = parentKey ? (bookmarkStore.order[parentKey] ?? []) : []
    const index = ids.indexOf(node.id)
    return { isFirst: index <= 0, isLast: index < 0 || index === ids.length - 1 }
  }

  /** 弹出文件夹选择器并落库。用户取消则什么都不做 */
  async function moveToFolder(node: UserLayoutNodeVO) {
    let destKey: string
    try {
      destKey = await useMovePickerStore().pick(node.id)
    } catch {
      return
    }
    const fromKey = bookmarkStore.parentKeyOf(node.id) ?? ROOT_KEY
    if (destKey === fromKey) return

    // 追加到目标文件夹末尾；moveLocal 会在原文件夹因此只剩 ≤1 项时自动就地解散
    const destLength = (bookmarkStore.order[destKey] ?? []).length
    bookmarkStore.moveLocal(node.id, destKey, destLength)
    persistOrder('move')
    try {
      await bookmarksMoveNode(node.id, destKey === ROOT_KEY ? null : destKey)
      useToastStore().success('已移动')
    } catch (error) {
      console.error('[useBookmarkMove] 移动书签失败', error)
    }
  }

  /** 是否有可移动的去处 —— 只有根目录、没有任何文件夹时，「移动到…」没有意义 */
  const hasMoveTargets = computed(
    () => bookmarkStore.rootNodes.some((n) => n.type === HomeItemType.BOOKMARK_DIR),
  )

  return { moveWithin, moveToFolder, positionOf, hasMoveTargets }
}
