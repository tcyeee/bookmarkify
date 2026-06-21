<template>
  <!-- 独立验证页：/lab-drag —— 仅验证 Pragmatic DnD 的 ①拖拽排序 ②拖到图标上建文件夹 -->
  <div class="min-h-screen bg-neutral-800 p-10 text-white">
    <div class="mb-6 space-y-1 text-sm text-white/70">
      <div class="text-base font-semibold text-white">Pragmatic DnD 最小验证</div>
      <div>· 拖动图标到「另一个图标中心」→ 合并为文件夹（边框高亮时松手）</div>
      <div>· 拖动到「两个图标之间」→ 排序（出现竖线指示）</div>
      <div>· 控制台有 <code>[lab]</code> 全程日志</div>
    </div>

    <div ref="gridRef" class="flex flex-wrap gap-4" style="max-width: 760px">
      <div
        v-for="item in items"
        :key="item.id"
        :data-cell-id="item.id"
        class="lab-cell relative flex h-24 w-24 select-none flex-col items-center justify-center rounded-2xl text-3xl shadow transition-[outline,box-shadow]"
        :class="[
          dropTargetId === item.id && dropMode === 'folder' ? 'outline outline-4 outline-white' : '',
          dropTargetId === item.id && dropMode === 'left' ? 'edge-left' : '',
          dropTargetId === item.id && dropMode === 'right' ? 'edge-right' : '',
          draggingId === item.id ? 'opacity-40' : '',
        ]"
        :style="{ background: item.kind === 'folder' ? 'rgba(255,255,255,0.18)' : item.color }">
        <template v-if="item.kind === 'folder'">
          <div class="grid grid-cols-2 gap-0.5">
            <span v-for="c in item.children.slice(0, 4)" :key="c.id" class="h-8 w-8 rounded text-base flex items-center justify-center" :style="{ background: c.color }">{{ c.emoji }}</span>
          </div>
          <span class="mt-1 text-[11px] text-white/80">文件夹({{ item.children.length }})</span>
        </template>
        <template v-else>
          <span>{{ item.emoji }}</span>
          <span class="mt-1 text-[11px] text-white/80">{{ item.id }}</span>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { draggable, dropTargetForElements, monitorForElements } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import { combine } from '@atlaskit/pragmatic-drag-and-drop/combine'

definePageMeta({ layout: 'default' })

const log = (...a: unknown[]) => console.log('%c[lab]', 'color:#16a34a;font-weight:bold', ...a)

interface Leaf { id: string; kind: 'app'; emoji: string; color: string }
interface Folder { id: string; kind: 'folder'; children: Leaf[] }
type Cell = Leaf | Folder

const palette = ['#ef4444', '#f59e0b', '#10b981', '#3b82f6', '#8b5cf6', '#ec4899', '#14b8a6', '#f97316']
const emojis = ['🍎', '🎮', '🎵', '📚', '🎨', '🛒', '⚙️', '📷']
const items = ref<Cell[]>(
  Array.from({ length: 8 }, (_, i) => ({ id: `app${i + 1}`, kind: 'app', emoji: emojis[i], color: palette[i] }) as Leaf),
)

const draggingId = ref<string | null>(null)
const dropTargetId = ref<string | null>(null)
const dropMode = ref<'folder' | 'left' | 'right' | null>(null)

const gridRef = ref<HTMLElement | null>(null)
let cleanup: (() => void) | null = null

// 命中判定：光标落在目标中心 50% 区 → folder；偏左半 → 插到左侧；偏右半 → 插到右侧
function classify(el: HTMLElement, clientX: number) {
  const r = el.getBoundingClientRect()
  const ratio = (clientX - r.left) / r.width
  if (ratio > 0.3 && ratio < 0.7) return 'folder' as const
  return ratio <= 0.3 ? ('left' as const) : ('right' as const)
}

function registerCells() {
  cleanup?.()
  const cells = Array.from(gridRef.value?.querySelectorAll<HTMLElement>('[data-cell-id]') ?? [])
  log('注册单元格', { count: cells.length })
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
        onDrop: () => {
          log('↑ drop(draggable)', { id })
          draggingId.value = null
          dropTargetId.value = null
          dropMode.value = null
        },
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
            log('◎ over', { target: id, mode })
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
        const target = location.current.dropTargets[0]
        log('★ monitor.onDrop', { source: source.data.id, target: target?.data?.id, mode: dropMode.value })
        if (!target) return
        commit(String(source.data.id), String(target.data.id), dropMode.value)
      },
    }),
  )
}

// 统一提交：folder=建夹/并入；left/right=排序
function commit(sourceId: string, targetId: string, mode: 'folder' | 'left' | 'right' | null) {
  const list = items.value
  const si = list.findIndex((c) => c.id === sourceId)
  const ti = list.findIndex((c) => c.id === targetId)
  if (si < 0 || ti < 0 || si === ti) return
  const source = list[si]

  if (mode === 'folder') {
    const target = list[ti]
    const movedLeaf = source.kind === 'app' ? source : null
    if (!movedLeaf) {
      log('暂不支持把文件夹拖进别处（原型）')
      return
    }
    if (target.kind === 'folder') {
      target.children.push(movedLeaf)
      list.splice(si, 1)
      log('→ 移入文件夹', { folder: target.id, child: movedLeaf.id })
    } else {
      const folder: Folder = { id: `dir-${Date.now().toString(36)}`, kind: 'folder', children: [target, movedLeaf] }
      // 用文件夹替换 target，删除 source
      const keep = list.filter((c) => c.id !== sourceId && c.id !== targetId)
      const insertAt = keep.findIndex((c) => c.id === (list[Math.min(ti, list.length - 1)]?.id))
      const at = insertAt >= 0 ? insertAt : keep.length
      keep.splice(at, 0, folder)
      items.value = keep
      log('→ 新建文件夹', { folder: folder.id, members: [target.id, movedLeaf.id] })
    }
  } else {
    // 排序：把 source 移到 target 左/右
    const moved = list.splice(si, 1)[0]
    const newTi = list.findIndex((c) => c.id === targetId)
    const at = mode === 'right' ? newTi + 1 : newTi
    list.splice(at, 0, moved)
    log('→ 排序', { source: sourceId, target: targetId, mode, at })
  }
  // DOM 变了，重新注册（原型简单做法；正式版用 key 稳定 + watch）
  nextTick(registerCells)
}

onMounted(() => nextTick(registerCells))
onBeforeUnmount(() => cleanup?.())
</script>

<style scoped>
.lab-cell.edge-left::before,
.lab-cell.edge-right::before {
  content: '';
  position: absolute;
  top: -4px;
  bottom: -4px;
  width: 4px;
  border-radius: 2px;
  background: #fff;
}
.lab-cell.edge-left::before {
  left: -10px;
}
.lab-cell.edge-right::before {
  right: -10px;
}
</style>
