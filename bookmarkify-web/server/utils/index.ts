import { CurrentEnvironment } from '@typing'
import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'
import { nanoid } from 'nanoid'
import { md5 } from 'js-md5'

export { md5 }

export function cn(...inputs: ClassValue[]) {
  const result = twMerge(clsx(...inputs))
  console.log(`[cn] 合并 class: inputs=${JSON.stringify(inputs)}, result="${result}"`)
  return result
}

export function randomId() {
  const id = nanoid().slice(0, 8)
  console.log(`[randomId] 生成随机ID: ${id}`)
  return id
}

let timeLock: number = Number.MIN_SAFE_INTEGER

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
  console.log(`[limitAction] 开始限流检查: max=${max}, now=${now}, timeLock=${timeLock}, scop=${scop}`)

  // 如果时间锁时间小于当前+timeEnd,则放行
  if (timeLock > now + scop) {
    console.log(`[limitAction] 触发频率超限，拦截本次调用: timeLock=${timeLock} > now+scop=${now + scop}`)
    return
  }

  // 如果时间锁时间小于当前时间-timeEnd,则重置为当前时间并放行
  if (timeLock < now - scop) {
    console.log(`[limitAction] 时间锁已过期，重置为当前时间: timeLock=${timeLock} -> ${now}`)
    timeLock = now
  }
  timeLock += 60000
  console.log(`[limitAction] 通过限流检查，执行 action，timeLock 推进至: ${timeLock}`)

  // 调用action方法
  action()
}

export function getCurrentEnvironment(): CurrentEnvironment {
  const isDev = process.env.NODE_ENV === 'development' || process.env.NUXT_ENV === 'development'
  const env = isDev ? CurrentEnvironment.LOCAL : CurrentEnvironment.PROD
  console.log(`[getCurrentEnvironment] NODE_ENV=${process.env.NODE_ENV}, NUXT_ENV=${process.env.NUXT_ENV}, 判定环境: ${env}`)
  return env
}
