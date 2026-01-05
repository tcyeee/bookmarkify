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

    // 创建 FormData
    const formData = new FormData()
    formData.append('file', file)

    // 创建请求
    console.log(`[API] UPLOAD::${path}`)
    const token = authStore.account?.token ?? ''
    const request: Request = new Request(useRuntimeConfig().public.apiBase + path, {
      headers: { satoken: token },
      body: formData,
      method: 'POST',
    })

    return this.withDebounce(`UPLOAD:${request.url}`, async () => {
      try {
        const response = await fetch(request)
        const data = await response.json()
        return resultCheck(data as Result<object>, request)
      } catch (error) {
        // @ts-ignore
        if (error instanceof TypeError) ElMessage.error(`Oops,网络错误,请重试`)
        return Promise.reject(error)
      }
    })
  }

  static async start(path: string, method: string, params?: any): Promise<any> {
    const authStore = useAuthStore()

    // 除了Login，其他都需要token
    if (method != 'GET' && !path.startsWith('/auth/') && !authStore.account?.token) await authStore.loginOrRegister()

    // 创建请求
    console.log(`[API] ${method}::${path}`)
    if (method == 'GET' && params) path += `?${new URLSearchParams(params).toString()}`
    const body = method == 'POST' ? JSON.stringify(params) : undefined
    const request: Request = new Request(useRuntimeConfig().public.apiBase + path, {
      headers: { 'Content-Type': 'application/json', satoken: authStore.account?.token ?? '' },
      body: body,
      method: method,
    })

    return this.withDebounce(`${method}:${request.url}:${body ?? ''}`, async () => {
      const response = await fetch(request)
      const data = await response.json()
      return resultCheck(data as Result<object>, request)
    })
  }
}

// 对返回结果进行检查
async function resultCheck(result: Result<object>, request: Request): Promise<any> {
  if (request.method === 'OPTIONS') return Promise.resolve(null)
  if (result.ok) return Promise.resolve(result.data)
  // 如果遇到token失效,则重新登录
  if ([101].includes(result.code)) {
    const authStore = useAuthStore()
    const account: UserInfo = await authStore.loginOrRegister()
    if (account.token) {
      request.headers.set('satoken', account.token)
      return await fetch(request)
    }
  }

  // 如果result.code是“1”开头，则需要提示
  if (result.code.toString().startsWith('1')) {
    ElMessage.error(`Oops, ${result.msg}`)
    return Promise.reject(result)
  }
  // 如果result.code是“3”开头,则不提示,直接返回
  if (result.code.toString().startsWith('3')) return Promise.reject(result)

  ElMessage.error(`Oops,网络错误,请重试`)
  return Promise.reject(result)
}
