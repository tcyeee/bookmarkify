import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import type { Ref } from 'vue'
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
import { draggable, dropTargetForElements, monitorForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/combine'

/** 拖拽松手时上抛给页面的提交动作 */
export type DragCommit =
  | { kind: 'reorder'; ids: string[] }
  | { kind: 'merge'; draggedId: string; targetId: string; index: number }
  | { kind: 'moveInto'; draggedId: string; folderId: string }
  | { kind: 'eject'; draggedId: string }
  | { kind: 'none' }

type Mode = 'folder' | 'left' | 'right'

interface Cfg {
  /** 取网格根 DOM（cell 都带 data-cell-id） */
  gridEl: () => HTMLElement | null
  items: Ref<Array<UserLayoutNodeVO>>
  /** 主网格 true：可建夹/移入；文件夹内 false：禁套娃，folder 模式退化为排序 */
  allowFolder: boolean
  onCommit: (c: DragCommit) => void
}

const DEBUG = true
const log = (...a: unknown[]) => {
  if (DEBUG || (typeof window !== 'undefined' && (window as any).__DRAG_DEBUG__ === true))
    console.log('%c[dnd]', 'color:#16a34a;font-weight:bold', ...a)
}

/**
 * 基于 Atlassian Pragmatic DnD 的启动台拖拽。每个 cell 既是 draggable 又是 drop target，
 * 命中目标中心 50% → 建夹/移入；偏左右 → 排序。跨网格隔离：每个网格只处理「源在自己 items 里」的 drop。
 */
export function useLaunchpadDrag(cfg: Cfg) {
  const draggingId = ref<string | null>(null)
  const dropTargetId = ref<string | null>(null)
  const dropMode = ref<Mode | null>(null)
  let cleanup: (() => void) | null = null

  // 命中：水平中心 50% 区 → folder；偏左 → 左插；偏右 → 右插
  function classify(el: HTMLElement, clientX: number): Mode {
    const r = el.getBoundingClientRect()
    const ratio = (clientX - r.left) / r.width
    if (ratio >= 0.25 && ratio <= 0.75) return 'folder'
    return ratio < 0.25 ? 'left' : 'right'
  }

  function resetVisual() {
    draggingId.value = null
    dropTargetId.value = null
    dropMode.value = null
  }

  function register() {
    cleanup?.()
    const root = cfg.gridEl()
    const cells = Array.from(root?.querySelectorAll<HTMLElement>('[data-cell-id]') ?? [])
    log('注册 cell', { count: cells.length, allowFolder: cfg.allowFolder })
    const disposers = cells.map((el) => {
      const id = el.dataset.cellId!
      return combine(
        draggable({
          element: el,
          getInitialData: () => ({ id }),
          onDragStart: () => {
            draggingId.value = id
            log('↓ dragStart', { id })
          },
          onDrop: resetVisual,
        }),
        dropTargetForElements({
          element: el,
          canDrop: ({ source }) => source.data.id !== id,
          getData: () => ({ id }),
          onDrag: ({ location }) => {
            const mode = classify(el, location.current.input.clientX)
            if (dropTargetId.value !== id || dropMode.value !== mode) {
              dropTargetId.value = id
              dropMode.value = mode
            }
          },
          onDragLeave: () => {
            if (dropTargetId.value === id) {
              dropTargetId.value = null
              dropMode.value = null
            }
          },
        }),
      )
    })
    cleanup = combine(
      ...disposers,
      monitorForElements({
        onDrop: ({ source, location }) => {
          resetVisual()
          const target = location.current.dropTargets[0]
          const sourceId = String(source.data.id)
          // 跨网格隔离：只处理源属于本网格的 drop
          if (!cfg.items.value.some((n) => n.id === sourceId)) return
          if (!target) {
            log('★ drop 无目标', { sourceId })
            return
          }
          const mode = classify(target.element as HTMLElement, location.current.input.clientX)
          log('★ monitor.onDrop', { sourceId, targetId: target.data.id, mode })
          decide(sourceId, String(target.data.id), mode)
        },
      }),
    )
  }

  function decide(sourceId: string, targetId: string, mode: Mode) {
    if (sourceId === targetId) return
    const list = cfg.items.value
    const targetNode = list.find((n) => n.id === targetId)
    const ti = list.findIndex((n) => n.id === targetId)
    if (ti < 0) return

    if (mode === 'folder' && cfg.allowFolder) {
      if (targetNode?.type === HomeItemType.BOOKMARK_DIR) {
        cfg.onCommit({ kind: 'moveInto', draggedId: sourceId, folderId: targetId })
        return
      }
      if (targetNode?.type === HomeItemType.BOOKMARK) {
        cfg.onCommit({ kind: 'merge', draggedId: sourceId, targetId, index: ti })
        return
      }
      // 目标是 LOADING/FUNCTION 等不可建夹 → 落为排序
    }

    // 排序：算出新顺序 id 列表
    const ids = list.map((n) => n.id).filter((x) => x !== sourceId)
    const newTi = ids.indexOf(targetId)
    ids.splice(mode === 'right' ? newTi + 1 : newTi, 0, sourceId)
    cfg.onCommit({ kind: 'reorder', ids })
  }

  // items 变化（增删/换序）→ 重挂（新元素需注册）
  watch(
    () => cfg.items.value.map((n) => n.id).join(','),
    () => nextTick(register),
  )
  onMounted(() => nextTick(register))
  onBeforeUnmount(() => cleanup?.())

  return { draggingId, dropTargetId, dropMode }
}
