import { useMediaQuery } from '@vueuse/core'

/** 手机端判定，与 Tailwind 的 sm 断点（640px）对齐，供需要在 JS 侧分支的场景使用（CSS 断点覆盖不到，
 * 比如「手机端整个不渲染按钮、改走长按」这种不是简单显隐的差异）。*/
export function useIsMobile() {
  return useMediaQuery('(max-width: 640px)')
}
