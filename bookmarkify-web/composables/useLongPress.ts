import { ref } from 'vue'

/**
 * 手机端「长按打开菜单」的公共实现，配合 PinnedBookmarkGrid / BookmarkTreeRow 等组件里
 * 手机端隐藏掉的 ⋯ 按钮使用——桌面端仍走右键，触屏没有右键，长按是唯一入口。
 *
 * 一个实例可以在 v-for 里给多行共用：同一时刻只可能有一根手指按着，start() 在 touchstart
 * 时按具体的 node 传入回调，move 超过阈值（判定为滚动而非长按）就取消计时。
 *
 * pressingId 是判定成立前的按下态：长按有一段必然的等待（delay），完全没有反馈的话用户会以为
 * 按下没反应而提前松手。组件把它和自己行的 id 比对，drive 一段与 delay 等长的过渡动画，
 * 判定成立（或中途取消）那一刻动画同步归位——具体动画交给调用方的 CSS，这里只出状态。
 */
export function useLongPress(delay = 300, moveThreshold = 10) {
  const pressingId = ref<string | null>(null)
  let timer: ReturnType<typeof setTimeout> | null = null
  let startX = 0
  let startY = 0
  // 长按已经把菜单打开过一次：触屏上 touchend 之后浏览器仍会补发一次 click，
  // 不拦住的话会在打开菜单的同时把这次长按当成一次正常点击（例如导航跳走）。
  let suppressNextClick = false

  function clear() {
    if (timer) clearTimeout(timer)
    timer = null
    pressingId.value = null
  }

  function start(e: TouchEvent, id: string, onTrigger: (x: number, y: number) => void) {
    const touch = e.touches[0]
    if (!touch) return
    startX = touch.clientX
    startY = touch.clientY
    clear()
    pressingId.value = id
    timer = setTimeout(() => {
      suppressNextClick = true
      pressingId.value = null
      onTrigger(touch.clientX, touch.clientY)
    }, delay)
  }

  function move(e: TouchEvent) {
    const touch = e.touches[0]
    if (!touch) return
    if (Math.abs(touch.clientX - startX) > moveThreshold || Math.abs(touch.clientY - startY) > moveThreshold) clear()
  }

  function end() {
    clear()
  }

  function guardClick(e: MouseEvent) {
    if (!suppressNextClick) return
    suppressNextClick = false
    e.preventDefault()
    // stopPropagation 不够：同一元素上晚注册的 @click（比如 recordOpen）在 stopPropagation 之后
    // 仍会执行，只有 stopImmediatePropagation 能连同级监听器一起拦下
    e.stopImmediatePropagation()
  }

  return { pressingId, start, move, end, guardClick }
}
