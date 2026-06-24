import type { Result } from '@typing'
import { useAuthStore } from '@stores/auth.store'

export default class http {
  private static PENDING_DEBOUNCE_MS = 600
  private static pendingRequests = new Map<string, { promise: Promise<unknown>; timestamp: number }>()
  private static withDebounce<T>(key: string, runner: () => Promise<T>): Promise<T> {
    const now = Date.now()
    const cached = this.pendingRequests.get(key)
    if (cached && now - cached.timestamp < this.PENDING_DEBOUNCE_MS) return cached.promise as Promise<T>

    const promise = runner().finally(() => {
      setTimeout(() => this.pendingRequests.delete(key), this.PENDING_DEBOUNCE_MS)
    })
    this.pendingRequests.set(key, { promise, timestamp: now })
    return promise
  }

  static get<T = unknown>(path: string, params?: any): Promise<T> {
    return this.start<T>(path, 'GET', params)
  }

  static post<T = unknown>(path: string, params?: any): Promise<T> {
    return this.start<T>(path, 'POST', params)
  }

  static upload<T = unknown>(path: string, file: File): Promise<T> {
    return this.uploadFile<T>(path, file)
  }

  static uploadWithForm<T = unknown>(path: string, file: File, extra: Record<string, string | string[]>): Promise<T> {
    return this.uploadFormData<T>(path, (form) => {
      form.append('file', file)
      for (const [key, value] of Object.entries(extra)) {
        if (Array.isArray(value)) {
          value.forEach((v) => form.append(key, v))
        } else {
          form.append(key, value)
        }
      }
    })
  }

  static async uploadFormData<T = unknown>(path: string, buildForm: (form: FormData) => void): Promise<T> {
    const authStore = useAuthStore()

    // 上传是受保护操作,未登录直接登出并跳转欢迎页
    if (!authStore.account?.token) {
      await authStore.logout()
      return Promise.reject(new Error('未登录'))
    }

    const formData = new FormData()
    buildForm(formData)

    const url = useRuntimeConfig().public.apiBase + path
    console.log(`[API] UPLOAD::${path}`)

    const exec = async (): Promise<T> => {
      const token = authStore.account?.token ?? ''
      try {
        const response = await fetch(url, {
          method: 'POST',
          headers: { satoken: token },
          body: formData,
        })
        const data = (await response.json()) as Result<T>
        return (await handleResult(data)) as T
      } catch (error) {
        if ((error instanceof TypeError || error instanceof SyntaxError) && import.meta.client) ElMessage.error(`Oops,网络错误,请重试`)
        return Promise.reject(error)
      }
    }

    return this.withDebounce(`UPLOAD:${url}`, exec)
  }

  static async uploadFile<T = unknown>(path: string, file: File): Promise<T> {
    return this.uploadFormData<T>(path, (form) => form.append('file', file))
  }

  static async start<T = unknown>(path: string, method: string, params?: any): Promise<T> {
    const authStore = useAuthStore()

    // 除 /auth/** 外的写操作都需要登录;未登录则登出并跳转欢迎页
    if (method != 'GET' && !path.startsWith('/auth/') && !authStore.account?.token) {
      await authStore.logout()
      return Promise.reject(new Error('未登录'))
    }

    console.log(`[API] ${method}::${path}`)
    if (method == 'GET' && params) path += `?${new URLSearchParams(params).toString()}`
    const body = method !== 'GET' && params != null ? JSON.stringify(params) : undefined
    const url = useRuntimeConfig().public.apiBase + path

    // 每次请求都构造新的 fetch 调用,使用最新 token
    const exec = async (): Promise<T> => {
      const token = authStore.account?.token ?? ''
      try {
        const response = await fetch(url, {
          method,
          headers: { 'Content-Type': 'application/json', satoken: token },
          body,
        })
        const text = await response.text()
        if (!text) return null as T
        const data = JSON.parse(text) as Result<T>
        return (await handleResult(data)) as T
      } catch (error) {
        if ((error instanceof TypeError || error instanceof SyntaxError) && import.meta.client) ElMessage.error(`Oops,网络错误,请重试`)
        return Promise.reject(error)
      }
    }

    return this.withDebounce(`${method}:${url}:${body ?? ''}`, exec)
  }
}

// 对返回结果进行检查
async function handleResult<T>(result: Result<T>): Promise<T> {
  if (result.ok) return result.data

  // token 失效(101):登出并跳转欢迎页,不再静默重新登录
  if (result.code === 101) {
    const authStore = useAuthStore()
    await authStore.logout()
    return Promise.reject(result)
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
