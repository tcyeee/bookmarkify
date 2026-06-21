<template>
  <!-- 独立验证页：/lab-drag —— 验证 Pragmatic DnD ①拖拽排序(带动画) ②拖到图标中心建文件夹 -->
  <div class="min-h-screen bg-neutral-800 p-10 text-white">
    <div class="mb-6 space-y-1 text-sm text-white/70">
      <div class="text-base font-semibold text-white">Pragmatic DnD 最小验证</div>
      <div>· 拖到「另一个图标中心」(出现白色边框)松手 → 合并/移入文件夹</div>
      <div>· 拖到「两个图标之间」(出现竖线)松手 → 排序</div>
      <div>· 控制台有 <code>[lab]</code> 全程日志</div>
    </div>

    <TransitionGroup ref="gridRef" name="cell" tag="div" class="flex flex-wrap gap-4" style="max-width: 760px">
      <div
        v-for="item in items"
        :key="item.id"
        :data-cell-id="item.id"
        class="lab-cell relative flex h-24 w-24 select-none flex-col items-center justify-center rounded-2xl text-3xl shadow"
        :class="[
          dropTargetId === item.id && dropMode === 'folder' ? 'outline outline-4 outline-white' : '',
          dropTargetId === item.id && dropMode === 'left' ? 'edge-left' : '',
          dropTargetId === item.id && dropMode === 'right' ? 'edge-right' : '',
          draggingId === item.id ? 'opacity-40' : '',
        ]"
        :style="{ background: item.kind === 'folder' ? 'rgba(255,255,255,0.18)' : item.color }">
        <template v-if="item.kind === 'folder'">
          <div class="grid grid-cols-2 gap-0.5">
            <span v-for="c in item.children.slice(0, 4)" :key="c.id" class="flex h-8 w-8 items-center justify-center rounded text-base" :style="{ background: c.color }">{{ c.emoji }}</span>
          </div>
          <span class="mt-1 text-[11px] text-white/80">文件夹({{ item.children.length }})</span>
        </template>
        <template v-else>
          <span>{{ item.emoji }}</span>
          <span class="mt-1 text-[11px] text-white/80">{{ item.id }}</span>
        </template>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, nextTick } from 'vue'
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

const gridRef = ref<any>(null)
let cleanup: (() => void) | null = null

type Mode = 'folder' | 'left' | 'right'
// 命中判定：光标落在目标水平中心 50% 区 → folder；偏左 → 左插；偏右 → 右插
function classify(el: HTMLElement, clientX: number): Mode {
  const r = el.getBoundingClientRect()
  const ratio = (clientX - r.left) / r.width
  if (ratio >= 0.25 && ratio <= 0.75) return 'folder'
  return ratio < 0.25 ? 'left' : 'right'
}

function gridEl(): HTMLElement | null {
  // TransitionGroup 的根 DOM
  return (gridRef.value?.$el as HTMLElement) ?? null
}

function registerCells() {
  cleanup?.()
  const cells = Array.from(gridEl()?.querySelectorAll<HTMLElement>('[data-cell-id]') ?? [])
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
          // 仅复位视觉态；真正的提交在 monitor.onDrop 里现算，不依赖这里的 ref
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
        if (!target) {
          log('★ drop: 无目标，忽略')
          return
        }
        // ★ 关键修复：用松手瞬间的指针 + 目标元素现算模式，绝不依赖会被重置的 dropMode ref
        const mode = classify(target.element as HTMLElement, location.current.input.clientX)
        log('★ monitor.onDrop', { source: source.data.id, target: target.data.id, mode })
        commit(String(source.data.id), String(target.data.id), mode)
      },
    }),
  )
}

function commit(sourceId: string, targetId: string, mode: Mode) {
  const list = items.value
  const si = list.findIndex((c) => c.id === sourceId)
  const ti = list.findIndex((c) => c.id === targetId)
  if (si < 0 || ti < 0 || si === ti) return
  const source = list[si]

  if (mode === 'folder') {
    if (source.kind !== 'app') {
      log('原型暂不支持拖动文件夹本身')
      return
    }
    const target = list[ti]
    if (target.kind === 'folder') {
      const next = list.filter((c) => c.id !== sourceId)
      ;(next.find((c) => c.id === targetId) as Folder).children.push(source)
      items.value = next
      log('→ 移入文件夹', { folder: targetId, child: sourceId })
    } else {
      const folder: Folder = { id: `dir-${Date.now().toString(36)}`, kind: 'folder', children: [target, source] }
      const next: Cell[] = []
      for (const c of list) {
        if (c.id === sourceId) continue
        next.push(c.id === targetId ? folder : c)
      }
      items.value = next
      log('→ 新建文件夹', { folder: folder.id, members: [targetId, sourceId] })
    }
  } else {
    const moved = list.splice(si, 1)[0]
    const newTi = list.findIndex((c) => c.id === targetId)
    list.splice(mode === 'right' ? newTi + 1 : newTi, 0, moved)
    items.value = [...list]
    log('→ 排序', { source: sourceId, target: targetId, mode })
  }
  // 列表变了，重挂拖拽（keyed 元素复用，新建项需注册）
  nextTick(registerCells)
}

onMounted(() => nextTick(registerCells))
onBeforeUnmount(() => cleanup?.())
</script>

<style scoped>
/* 重排位移动画（FLIP，由 TransitionGroup 自动加 transform 过渡）*/
.cell-move {
  transition: transform 0.28s cubic-bezier(0.2, 0, 0, 1);
}
.cell-enter-active,
.cell-leave-active {
  transition: all 0.22s ease;
}
.cell-enter-from,
.cell-leave-to {
  opacity: 0;
  transform: scale(0.6);
}
.cell-leave-active {
  position: absolute;
}

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
