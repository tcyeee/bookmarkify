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

// ── 调试日志 ──────────────────────────────────────────────────────────────────
// DEBUG: 总开关（生命周期关键事件/状态转换）；VERBOSE_MOVE: 每次 pointermove 都打（量很大）
// 在浏览器控制台可临时改：window.__DRAG_DEBUG__ = true / window.__DRAG_VERBOSE__ = true
const DEBUG = true
function dbgOn() {
  return DEBUG || (typeof window !== 'undefined' && (window as any).__DRAG_DEBUG__ === true)
}
function verboseOn() {
  return typeof window !== 'undefined' && (window as any).__DRAG_VERBOSE__ === true
}
let dragSeq = 0 // 每次拖拽的序号，方便在控制台按 # 分组
function log(...a: unknown[]) {
  if (dbgOn()) console.log('%c[drag]', 'color:#16a34a;font-weight:bold', ...a)
}
function warn(...a: unknown[]) {
  if (dbgOn()) console.warn('%c[drag]', 'color:#d97706;font-weight:bold', ...a)
}
function vlog(...a: unknown[]) {
  if (verboseOn()) console.debug('%c[drag·move]', 'color:#64748b', ...a)
}

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
  let lastSlot = -1 // 仅用于日志：槽位变化时才打
  const scope = cfg.isFolder ? 'folder' : 'root'
  const isDragging = computed(() => draggingId.value !== null && started)

  function clearMerge(reason?: string) {
    if (mergeTargetId.value || mergeReady.value) log(`#${dragSeq} 清除合并状态`, { reason, was: mergeTargetId.value })
    if (mergeTimer) {
      clearTimeout(mergeTimer)
      mergeTimer = null
    }
    mergeTargetId.value = null
    mergeReady.value = false
  }

  function onPointerDown(e: PointerEvent, id: string) {
    log(`#${dragSeq + 1} ↓ pointerdown`, {
      scope,
      id,
      button: e.button,
      pointerType: e.pointerType,
      client: [Math.round(e.clientX), Math.round(e.clientY)],
      itemsCount: cfg.items.value.length,
      containerExists: !!cfg.containerRef.value,
    })
    if (e.button !== 0) {
      log(`#${dragSeq + 1} 非左键，忽略`)
      return
    }
    // 抑制原生 HTML 拖放/文本选择，否则浏览器会用原生 drag 劫持 pointer 事件（pointermove 不再派发）
    e.preventDefault()
    dragSeq++
    draggingId.value = id
    started = false
    lastSlot = -1
    startX = e.clientX
    startY = e.clientY
    const rect = cfg.containerRef.value?.getBoundingClientRect()
    const idx = cfg.items.value.findIndex((n) => n.id === id)
    const p = cfg.layout.posOf(Math.max(0, idx))
    grabDX = e.clientX - ((rect?.left ?? 0) + p.x)
    grabDY = e.clientY - ((rect?.top ?? 0) + p.y)
    previewIds.value = cfg.items.value.map((n) => n.id)
    log(`#${dragSeq} 记录起手`, {
      idx,
      slotPos: [Math.round(p.x), Math.round(p.y)],
      containerRect: rect ? { left: Math.round(rect.left), top: Math.round(rect.top), w: Math.round(rect.width) } : null,
      grab: [Math.round(grabDX), Math.round(grabDY)],
      cols: cfg.layout.cols.value,
    })
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp, { once: true })
    window.addEventListener('pointercancel', onCancel, { once: true })
    log(`#${dragSeq} 已挂 window pointermove/up/cancel 监听`)
  }

  function onMove(e: PointerEvent) {
    if (!draggingId.value) return
    if (!started) {
      const dist = Math.hypot(e.clientX - startX, e.clientY - startY)
      vlog(`#${dragSeq} 阈值前移动`, { dist: Math.round(dist), threshold: DRAG_THRESHOLD })
      if (dist < DRAG_THRESHOLD) return
      started = true
      log(`#${dragSeq} ▶ 越过阈值，拖拽正式开始`, { dist: Math.round(dist) })
    }
    const rect = cfg.containerRef.value?.getBoundingClientRect()
    if (!rect) {
      warn(`#${dragSeq} containerRect 为空，跳过本次 move`)
      return
    }
    pointer.value = { x: e.clientX - rect.left - grabDX, y: e.clientY - rect.top - grabDY }
    vlog(`#${dragSeq} move`, {
      client: [Math.round(e.clientX), Math.round(e.clientY)],
      pointer: [Math.round(pointer.value.x), Math.round(pointer.value.y)],
    })

    // ① 文件夹内：中心移出浮层边界（含滞回）→ 武装弹出
    if (cfg.isFolder && cfg.folderBoundsRef?.value) {
      const b = cfg.folderBoundsRef.value.getBoundingClientRect()
      const cx = e.clientX
      const cy = e.clientY
      const outside =
        cx < b.left - EJECT_MARGIN || cx > b.right + EJECT_MARGIN || cy < b.top - EJECT_MARGIN || cy > b.bottom + EJECT_MARGIN
      if (outside !== ejectArmed.value) log(`#${dragSeq} 弹出状态变化`, { ejectArmed: outside })
      ejectArmed.value = outside
      if (outside) {
        clearMerge('eject-armed')
        return
      }
    }

    // ② 合并/移入意图：光标中心落在某目标内圈（中心 70%）且停留 300ms
    const target = hitInnerZone(e.clientX, e.clientY)
    if (target) {
      if (target.id !== mergeTargetId.value) {
        clearMerge('new-target')
        mergeTargetId.value = target.id
        const tNode = cfg.items.value.find((n) => n.id === target.id)
        log(`#${dragSeq} ◎ 命中目标内圈，启动 ${MERGE_DELAY}ms 计时`, { targetId: target.id, targetType: tNode?.type })
        mergeTimer = setTimeout(() => {
          mergeReady.value = true
          log(`#${dragSeq} ✦ 合并就绪（300ms 满）`, { targetId: target.id })
        }, MERGE_DELAY)
      }
      return // 抑制重排
    }
    if (mergeTargetId.value) clearMerge('left-target')

    // ③ 普通重排预览：把 draggingId 移到光标所在槽位
    const cxL = e.clientX - rect.left
    const cyL = e.clientY - rect.top
    const slot = Math.min(cfg.layout.indexAt(cxL, cyL), cfg.items.value.length - 1)
    const ids = cfg.items.value.map((n) => n.id).filter((x) => x !== draggingId.value)
    ids.splice(Math.max(0, slot), 0, draggingId.value)
    previewIds.value = ids
    if (slot !== lastSlot) {
      log(`#${dragSeq} ↕ 重排预览槽位变化`, { slot, local: [Math.round(cxL), Math.round(cyL)] })
      lastSlot = slot
    }
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

  function teardown() {
    window.removeEventListener('pointermove', onMove)
  }

  function onCancel(e: PointerEvent) {
    warn(`#${dragSeq} ✕ pointercancel（可能被原生拖放/手势打断）`, { pointerType: e.pointerType })
    teardown()
    draggingId.value = null
    started = false
    clearMerge('cancel')
    ejectArmed.value = false
    previewIds.value = []
    cfg.onCommit({ kind: 'none' })
  }

  function onUp(e: PointerEvent) {
    teardown()
    const dragged = draggingId.value
    const wasStarted = started
    const merge = mergeReady.value ? mergeTargetId.value : null
    const eject = ejectArmed.value
    const finalIds = [...previewIds.value]
    log(`#${dragSeq} ↑ pointerup`, {
      scope,
      dragged,
      wasStarted,
      mergeReadyTarget: merge,
      ejectArmed: eject,
      client: [Math.round(e.clientX), Math.round(e.clientY)],
    })
    // 复位
    draggingId.value = null
    started = false
    clearMerge('pointerup')
    ejectArmed.value = false
    previewIds.value = []

    if (!dragged || !wasStarted) {
      log(`#${dragSeq} → 提交 none（未真正拖动，视为点击）`)
      cfg.onCommit({ kind: 'none' })
      return
    }
    if (cfg.isFolder && eject) {
      log(`#${dragSeq} → 提交 eject`, { draggedId: dragged })
      cfg.onCommit({ kind: 'eject', draggedId: dragged })
      return
    }
    if (merge) {
      const node = cfg.items.value.find((n) => n.id === merge)
      if (node?.type === HomeItemType.BOOKMARK_DIR) {
        log(`#${dragSeq} → 提交 moveInto`, { draggedId: dragged, folderId: merge })
        cfg.onCommit({ kind: 'moveInto', draggedId: dragged, folderId: merge })
      } else {
        const index = cfg.items.value.findIndex((n) => n.id === merge)
        log(`#${dragSeq} → 提交 merge（建夹）`, { draggedId: dragged, targetId: merge, index })
        cfg.onCommit({ kind: 'merge', draggedId: dragged, targetId: merge, index })
      }
      return
    }
    log(`#${dragSeq} → 提交 reorder`, { ids: finalIds })
    cfg.onCommit({ kind: 'reorder', ids: finalIds })
  }

  return { draggingId, previewIds, mergeTargetId, mergeReady, ejectArmed, pointer, isDragging, onPointerDown }
}
