import { computed, onMounted, onBeforeUnmount, ref, type Ref } from 'vue'
import { usePreferenceStore } from '@stores/preference.store'

/**
 * 等大格子布局：列数随容器宽度变化，index ↔ 坐标为纯函数。
 * 取代 Muuri 的布局引擎——启动台每格尺寸相同，定位只是除法。
 */
export function useGridLayout(containerRef: Ref<HTMLElement | null>, opts?: { titleHeight?: number }) {
  const pref = usePreferenceStore()
  const cellW = computed(() => pref.bookmarkCellSizePx)
  const cellH = computed(() => pref.bookmarkCellSizePx + (opts?.titleHeight ?? (pref.preference?.showTitle ? 28 : 0)))
  const gap = computed(() => pref.bookmarkGapPx)
  const colWidth = computed(() => cellW.value + gap.value)
  const rowHeight = computed(() => cellH.value + gap.value)

  const cols = ref(1)
  const recalc = () => {
    const w = containerRef.value?.clientWidth ?? 0
    cols.value = Math.max(1, Math.floor((w + gap.value) / colWidth.value))
  }

  let ro: ResizeObserver | null = null
  onMounted(() => {
    recalc()
    ro = new ResizeObserver(recalc)
    if (containerRef.value) ro.observe(containerRef.value)
    window.addEventListener('resize', recalc)
  })
  onBeforeUnmount(() => {
    ro?.disconnect()
    window.removeEventListener('resize', recalc)
  })

  const posOf = (index: number) => ({
    x: (index % cols.value) * colWidth.value,
    y: Math.floor(index / cols.value) * rowHeight.value,
  })
  const gridWidth = computed(() => cols.value * colWidth.value)
  const gridHeight = (count: number) => Math.max(1, Math.ceil(count / cols.value)) * rowHeight.value
  // 容器内坐标 → 槽位 index（clamp 由调用方按 count 处理）
  const indexAt = (localX: number, localY: number) => {
    const c = Math.max(0, Math.min(cols.value - 1, Math.floor(localX / colWidth.value)))
    const r = Math.max(0, Math.floor(localY / rowHeight.value))
    return r * cols.value + c
  }

  return { cols, cellW, cellH, gap, colWidth, rowHeight, posOf, gridWidth, gridHeight, indexAt, recalc }
}
