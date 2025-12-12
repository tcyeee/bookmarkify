import gsap from 'gsap'
import ScrollTrigger from 'gsap/dist/ScrollTrigger'
import Lenis from 'lenis'

/**
 * 仅在客户端启用平滑滚动，避免 SSR 阶段引用 window/navigator 导致报错。
 * 返回清理函数以便组件卸载时移除监听。
 */
const useSmoothScroll = () => {
  if (!import.meta.client) return

  gsap.registerPlugin(ScrollTrigger)

  const lenis = new Lenis()

  lenis.on('scroll', ScrollTrigger.update)

  const tick = (time: number) => lenis.raf(time * 1000)

  gsap.ticker.add(tick)
  gsap.ticker.lagSmoothing(0)

  return () => {
    gsap.ticker.remove(tick)
    lenis.destroy()
  }
}

export { useSmoothScroll }
