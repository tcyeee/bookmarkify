import type { UserFile } from '@typing'

/**
 * 单位时间内触发事件
 *
 * @param max {max}分钟内最多触发{max}次
 * @param action 触发的方法
 * @returns
 */
export function limitAction(max: number, action: Function): void {
  max = max < 2 ? 2 : max
  let now = Number(Date.now())
  let scop = (max - 1) * 60 * 1000
  let timeLock = Number(localStorage.getItem('timeLockStr'))

  // 如果时间锁时间小于当前+timeEnd,则放行
  if (timeLock > now + scop) return

  // 如果时间锁时间小于当前时间-timeEnd,则重置为当前时间并放行
  if (timeLock < now - scop) timeLock = now
  timeLock += 60000
  localStorage.setItem('timeLockStr', String(timeLock))

  // 调用action方法
  action()
}
