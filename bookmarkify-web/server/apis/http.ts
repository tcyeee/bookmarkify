import type { Result } from '@typing'
import { useAuthStore } from '@stores/auth.store'
import { useToastStore } from '@stores/toast.store'

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
    return this.uploadFormData<T>(
      path,
      (form) => {
        form.append('file', file)
        for (const [key, value] of Object.entries(extra)) {
          if (Array.isArray(value)) {
            value.forEach((v) => form.append(key, v))
          } else {
            form.append(key, value)
          }
        }
      },
      fileIdentity(file),
    )
  }

  // dedupeKey 需能区分"同一接口的不同文件",否则同一 600ms 窗口内连续上传不同文件(如换头像后立刻再上传一次)
  // 会被误判为重复请求,后一次直接复用前一次的缓存结果,新文件根本不会被发送到服务端。
  static async uploadFormData<T = unknown>(path: string, buildForm: (form: FormData) => void, dedupeKey = ''): Promise<T> {
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
        return (await handleResult(data, `UPLOAD::${path}`)) as T
      } catch (error) {
        if ((error instanceof TypeError || error instanceof SyntaxError) && import.meta.client) {
          console.error(`[API] 网络错误 UPLOAD::${path}`, error)
          useToastStore().error(`Oops,网络错误(UPLOAD ${path}),请重试`)
        }
        return Promise.reject(error)
      }
    }

    return this.withDebounce(`UPLOAD:${url}:${dedupeKey}`, exec)
  }

  static async uploadFile<T = unknown>(path: string, file: File): Promise<T> {
    return this.uploadFormData<T>(path, (form) => form.append('file', file), fileIdentity(file))
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
        return (await handleResult(data, `${method}::${path}`)) as T
      } catch (error) {
        if ((error instanceof TypeError || error instanceof SyntaxError) && import.meta.client) {
          console.error(`[API] 网络错误 ${method}::${path}`, error)
          useToastStore().error(`Oops,网络错误(${method} ${path}),请重试`)
        }
        return Promise.reject(error)
      }
    }

    return this.withDebounce(`${method}:${url}:${body ?? ''}`, exec)
  }
}

// 文件身份标识，用于让上传请求的去重 key 区分不同文件(避免连续上传不同文件被误判为重复请求)
function fileIdentity(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`
}

// 对返回结果进行检查；requestLabel 形如 "GET::/user/info"，用于让报错能定位到具体请求
async function handleResult<T>(result: Result<T>, requestLabel: string): Promise<T> {
  if (result.ok) return result.data

  // token 失效(101):登出并跳转欢迎页,不再静默重新登录
  // 必须 skipServerLogout=true:服务端已判定会话失效才会返回 101,此时再调用 /auth/logout
  // 大概率再次收到 101,而 withDebounce 会把递归产生的这次调用去重成同一个 in-flight promise,
  // 导致外层 logout() 永远等不到内层结果、卡在 finally 之前——cleanup 和 navigateTo 都不会执行。
  if (result.code === 101) {
    const authStore = useAuthStore()
    await authStore.logout(true)
    return Promise.reject(result)
  }

  // 如果result.code是“1”开头，则需要提示
  if (result.code.toString().startsWith('1')) {
    if (import.meta.client) useToastStore().error(`Oops, ${result.msg}`)
    return Promise.reject(result)
  }
  // 如果result.code是”3”开头,则不提示,直接返回
  if (result.code.toString().startsWith('3')) return Promise.reject(result)

  if (import.meta.client) {
    console.error(`[API] 未知响应码 ${requestLabel}`, result)
    useToastStore().error(`Oops,网络错误(${requestLabel}),请重试`)
  }
  return Promise.reject(result)
}
