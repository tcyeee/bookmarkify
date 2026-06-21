import { ref, computed, type Ref } from 'vue'
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
import type { useGridLayout } from './useGridLayout'

/** 拖拽松手时上抛给页面的提交动作 */
export type DragCommit =
  | { kind: 'reorder'; ids: string[] }
  | { kind: 'merge'; draggedId: string; targetId: string; index: number }
  | { kind: 'moveInto'; draggedId: string; folderId: string }
  | { kind: 'eject'; draggedId: string }
  | { kind: 'none' }

interface DragCfg {
  containerRef: Ref<HTMLElement | null>
  items: Ref<Array<UserLayoutNodeVO>>
  layout: ReturnType<typeof useGridLayout>
  isFolder: boolean
  folderBoundsRef?: Ref<HTMLElement | null>
  onCommit: (c: DragCommit) => void
}

const MERGE_DELAY = 300
const EJECT_MARGIN = 40
const DRAG_THRESHOLD = 8

/**
 * 唯一拖拽控制器（root 网格与文件夹浮层共用）。
 * 容器内拖拽实时重排预览；跨容器移动（建夹/移入/弹出）一律在 pointerup 那一刻提交。
 * 无跨网格实时迁移 —— 根除旧实现 receive/send 时序 bug。
 */
export function useLaunchpadDrag(cfg: DragCfg) {
  const draggingId = ref<string | null>(null)
  const previewIds = ref<string[]>([]) // 拖拽中容器内的实时顺序预览
  const mergeTargetId = ref<string | null>(null)
  const mergeReady = ref(false)
  const ejectArmed = ref(false)
  const pointer = ref({ x: 0, y: 0 }) // 被拖 cell 相对容器左上的渲染位置

  let startX = 0
  let startY = 0
  let grabDX = 0
  let grabDY = 0
  let started = false
  let mergeTimer: ReturnType<typeof setTimeout> | null = null
  const isDragging = computed(() => draggingId.value !== null && started)

  function clearMerge() {
    if (mergeTimer) {
      clearTimeout(mergeTimer)
      mergeTimer = null
    }
    mergeTargetId.value = null
    mergeReady.value = false
  }

  function onPointerDown(e: PointerEvent, id: string) {
    if (e.button !== 0) return
    draggingId.value = id
    started = false
    startX = e.clientX
    startY = e.clientY
    const rect = cfg.containerRef.value?.getBoundingClientRect()
    const idx = cfg.items.value.findIndex((n) => n.id === id)
    const p = cfg.layout.posOf(Math.max(0, idx))
    grabDX = e.clientX - ((rect?.left ?? 0) + p.x)
    grabDY = e.clientY - ((rect?.top ?? 0) + p.y)
    previewIds.value = cfg.items.value.map((n) => n.id)
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp, { once: true })
  }

  function onMove(e: PointerEvent) {
    if (!draggingId.value) return
    if (!started) {
      if (Math.hypot(e.clientX - startX, e.clientY - startY) < DRAG_THRESHOLD) return
      started = true
    }
    const rect = cfg.containerRef.value?.getBoundingClientRect()
    if (!rect) return
    pointer.value = { x: e.clientX - rect.left - grabDX, y: e.clientY - rect.top - grabDY }

    // ① 文件夹内：中心移出浮层边界（含滞回）→ 武装弹出
    if (cfg.isFolder && cfg.folderBoundsRef?.value) {
      const b = cfg.folderBoundsRef.value.getBoundingClientRect()
      const cx = e.clientX
      const cy = e.clientY
      const outside =
        cx < b.left - EJECT_MARGIN || cx > b.right + EJECT_MARGIN || cy < b.top - EJECT_MARGIN || cy > b.bottom + EJECT_MARGIN
      ejectArmed.value = outside
      if (outside) {
        clearMerge()
        return
      }
    }

    // ② 合并/移入意图：光标中心落在某目标内圈（中心 70%）且停留 300ms
    const target = hitInnerZone(e.clientX, e.clientY)
    if (target) {
      if (target.id !== mergeTargetId.value) {
        clearMerge()
        mergeTargetId.value = target.id
        mergeTimer = setTimeout(() => {
          mergeReady.value = true
        }, MERGE_DELAY)
      }
      return // 抑制重排
    }
    if (mergeTargetId.value) clearMerge()

    // ③ 普通重排预览：把 draggingId 移到光标所在槽位
    const cxL = e.clientX - rect.left
    const cyL = e.clientY - rect.top
    const slot = Math.min(cfg.layout.indexAt(cxL, cyL), cfg.items.value.length - 1)
    const ids = cfg.items.value.map((n) => n.id).filter((x) => x !== draggingId.value)
    ids.splice(Math.max(0, slot), 0, draggingId.value)
    previewIds.value = ids
  }

  // 光标命中某非自身 BOOKMARK/文件夹的中心 70% 区
  function hitInnerZone(clientX: number, clientY: number): { id: string } | null {
    const rect = cfg.containerRef.value?.getBoundingClientRect()
    if (!rect) return null
    const ids = previewIds.value
    for (let i = 0; i < ids.length; i++) {
      const id = ids[i]
      if (id === draggingId.value) continue
      const node = cfg.items.value.find((n) => n.id === id)
      if (!node || (node.type !== HomeItemType.BOOKMARK && node.type !== HomeItemType.BOOKMARK_DIR)) continue
      const p = cfg.layout.posOf(i)
      const left = rect.left + p.x
      const top = rect.top + p.y
      const w = cfg.layout.cellW.value
      const h = cfg.layout.cellW.value // 命中用图标方区（不含标题）
      const zx1 = left + w * 0.15
      const zx2 = left + w * 0.85
      const zy1 = top + h * 0.15
      const zy2 = top + h * 0.85
      if (clientX >= zx1 && clientX <= zx2 && clientY >= zy1 && clientY <= zy2) return { id }
    }
    return null
  }

  function onUp() {
    window.removeEventListener('pointermove', onMove)
    const dragged = draggingId.value
    const wasStarted = started
    const merge = mergeReady.value ? mergeTargetId.value : null
    const eject = ejectArmed.value
    const finalIds = [...previewIds.value]
    // 复位
    draggingId.value = null
    started = false
    clearMerge()
    ejectArmed.value = false
    previewIds.value = []
    if (!dragged || !wasStarted) {
      cfg.onCommit({ kind: 'none' })
      return
    }

    if (cfg.isFolder && eject) {
      cfg.onCommit({ kind: 'eject', draggedId: dragged })
      return
    }
    if (merge) {
      const node = cfg.items.value.find((n) => n.id === merge)
      if (node?.type === HomeItemType.BOOKMARK_DIR) cfg.onCommit({ kind: 'moveInto', draggedId: dragged, folderId: merge })
      else cfg.onCommit({ kind: 'merge', draggedId: dragged, targetId: merge, index: cfg.items.value.findIndex((n) => n.id === merge) })
      return
    }
    cfg.onCommit({ kind: 'reorder', ids: finalIds })
  }

  return { draggingId, previewIds, mergeTargetId, mergeReady, ejectArmed, pointer, isDragging, onPointerDown }
}
