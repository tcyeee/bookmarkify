import type { UserInfo } from '@typing'
import type { Result } from '@typing'
import { useAuthStore } from '@stores/auth.store'

export default class http {
  private static PENDING_DEBOUNCE_MS = 600
  private static pendingRequests = new Map<string, { promise: Promise<any>; timestamp: number }>()
  private static withDebounce<T>(key: string, runner: () => Promise<T>): Promise<T> {
    const now = Date.now()
    const cached = this.pendingRequests.get(key)
    if (cached && now - cached.timestamp < this.PENDING_DEBOUNCE_MS) return cached.promise as Promise<T>

    const promise = runner().finally(() => {
      setTimeout(() => this.pendingRequests.delete(key), this.PENDING_DEBOUNCE_MS)
    }) as Promise<T>
    this.pendingRequests.set(key, { promise, timestamp: now })
    return promise
  }

  static get(path: string, params?: any): any {
    return this.start(path, 'GET', params)
  }

  static post(path: string, params?: any): any {
    return this.start(path, 'POST', params)
  }

  static async upload(path: string, file: File): Promise<any> {
    return this.uploadFile(path, file)
  }

  static async uploadFile(path: string, file: File): Promise<any> {
    const authStore = useAuthStore()

    if (!authStore.account?.token) await authStore.loginOrRegister()

    const formData = new FormData()
    formData.append('file', file)

    const url = useRuntimeConfig().public.apiBase + path
    console.log(`[API] UPLOAD::${path}`)

    const exec = async (retried: boolean): Promise<any> => {
      const token = authStore.account?.token ?? ''
      try {
        const response = await fetch(url, {
          method: 'POST',
          headers: { satoken: token },
          body: formData,
        })
        const data = (await response.json()) as Result<object>
        return await handleResult(data, () => exec(true), retried)
      } catch (error) {
        if (error instanceof TypeError && import.meta.client) ElMessage.error(`Oops,网络错误,请重试`)
        return Promise.reject(error)
      }
    }

    return this.withDebounce(`UPLOAD:${url}`, () => exec(false))
  }

  static async start(path: string, method: string, params?: any): Promise<any> {
    const authStore = useAuthStore()

    // 除了Login，其他都需要token
    if (method != 'GET' && !path.startsWith('/auth/') && !authStore.account?.token) await authStore.loginOrRegister()

    console.log(`[API] ${method}::${path}`)
    if (method == 'GET' && params) path += `?${new URLSearchParams(params).toString()}`
    const body = method !== 'GET' && params != null ? JSON.stringify(params) : undefined
    const url = useRuntimeConfig().public.apiBase + path

    // 关键修复:每次请求都构造新的 fetch 调用,使用最新 token
    // 旧实现复用同一 Request 对象,导致 POST body 流被消费后无法重试,
    // 且重试路径直接返回 Response 而未走 resultCheck 解析。
    const exec = async (retried: boolean): Promise<any> => {
      const token = authStore.account?.token ?? ''
      try {
        const response = await fetch(url, {
          method,
          headers: { 'Content-Type': 'application/json', satoken: token },
          body,
        })
        const text = await response.text()
        if (!text) return null
        const data = JSON.parse(text) as Result<object>
        return await handleResult(data, () => exec(true), retried)
      } catch (error) {
        if (error instanceof TypeError && import.meta.client) ElMessage.error(`Oops,网络错误,请重试`)
        return Promise.reject(error)
      }
    }

    return this.withDebounce(`${method}:${url}:${body ?? ''}`, () => exec(false))
  }
}

// 对返回结果进行检查
async function handleResult(result: Result<object>, retry: () => Promise<any>, retried: boolean): Promise<any> {
  if (result.ok) return result.data

  // 如果遇到token失效,则重新登录后用新 token 重试一次(限一次,避免循环)
  if (result.code === 101 && !retried) {
    const authStore = useAuthStore()
    const account: UserInfo = await authStore.loginOrRegister()
    if (account.token) return retry()
  }

  // 如果result.code是“1”开头，则需要提示
  if (result.code.toString().startsWith('1')) {
    if (import.meta.client) ElMessage.error(`Oops, ${result.msg}`)
    return Promise.reject(result)
  }
  // 如果result.code是”3”开头,则不提示,直接返回
  if (result.code.toString().startsWith('3')) return Promise.reject(result)

  if (import.meta.client) ElMessage.error(`Oops,网络错误,请重试`)
  return Promise.reject(result)
}
