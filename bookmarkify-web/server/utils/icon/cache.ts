/**
 * 图标字节的本地持久缓存。
 *
 * 阿里云 OSS 图片处理按次计费，`OssUtils.signAsset` 签出的地址只在同一时间窗口内保持字节不变
 * （见后端 signWithResize），窗口一过签名刷新、URL 跟着变，浏览器 HTTP 缓存也随之失效——图标
 * 又要真刷一次 OSS。这里用 IndexedDB 把图标字节按「路径 + 缩放参数」（剥离签名相关的易变
 * query 参数）持久缓存在本地：签名怎么变，缓存怎么命中，从根本上减少重复的 OSS 请求，
 * 命中时也无需等网络往返，首屏图标秒开。
 *
 * 这一层只管**拿到字节**。字节说明什么（主色、要不要留白）归 ./appearance。
 */

const ICON_DB_NAME = 'bookmarkify-icon-cache'
const ICON_STORE_NAME = 'icons'
const ICON_DB_VERSION = 1
// 兜底有效期：内容寻址的图标字节本就不会变，这个只防止极少数存量外链变更后本地长期陈旧
const ICON_CACHE_MAX_AGE_MS = 30 * 24 * 3600 * 1000
// GeneratePresignedUrlRequest 签出的 query 参数，每次签名都变，其余部分（含 x-oss-process）不变
const ICON_SIGNED_URL_VOLATILE_PARAMS = ['OSSAccessKeyId', 'Expires', 'Signature']

interface CachedIconRecord {
  blob: Blob
  cachedAt: number
}

let iconDbPromise: Promise<IDBDatabase> | null = null

function openIconDb(): Promise<IDBDatabase> {
  if (!iconDbPromise) {
    iconDbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(ICON_DB_NAME, ICON_DB_VERSION)
      req.onupgradeneeded = () => req.result.createObjectStore(ICON_STORE_NAME)
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
  }
  return iconDbPromise
}

/**
 * 由签名 URL 推导出与签名无关的稳定缓存 key：去掉阿里云 presign 的易变参数（每次都变），
 * 保留 pathname 与 x-oss-process（同一张图 + 同一缩放尺寸下恒定）。非本服务签发的外链
 * （不带这些 query 参数，如 signAsset 对外链原样返回的情形）原样拿整条 URL 作 key。
 */
export function iconCacheKey(url: string): string {
  try {
    const u = new URL(url)
    if (!ICON_SIGNED_URL_VOLATILE_PARAMS.some((p) => u.searchParams.has(p))) return url
    ICON_SIGNED_URL_VOLATILE_PARAMS.forEach((p) => u.searchParams.delete(p))
    return u.toString()
  } catch {
    return url
  }
}

async function getCachedIconBlob(key: string): Promise<Blob | undefined> {
  if (!import.meta.client) return undefined
  try {
    const db = await openIconDb()
    const record = await new Promise<CachedIconRecord | undefined>((resolve, reject) => {
      const tx = db.transaction(ICON_STORE_NAME, 'readonly')
      const req = tx.objectStore(ICON_STORE_NAME).get(key)
      req.onsuccess = () => resolve(req.result)
      req.onerror = () => reject(req.error)
    })
    if (!record || Date.now() - record.cachedAt > ICON_CACHE_MAX_AGE_MS) return undefined
    return record.blob
  } catch {
    return undefined
  }
}

async function putCachedIconBlob(key: string, blob: Blob): Promise<void> {
  if (!import.meta.client) return
  try {
    const db = await openIconDb()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(ICON_STORE_NAME, 'readwrite')
      tx.objectStore(ICON_STORE_NAME).put({ blob, cachedAt: Date.now() } satisfies CachedIconRecord, key)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    // 静默失败：缓存只是优化，隐私模式等 IndexedDB 不可用的场景不该影响图标正常展示
  }
}

// 同一 key 的并发请求共享一次网络请求，避免同一张图标在多处同时渲染时重复打到 OSS
const iconFetchInflight = new Map<string, Promise<Blob | undefined>>()

/**
 * 拿到某个签名 URL 对应的图标字节：命中本地缓存直接返回（零网络请求）；
 * 未命中则请求一次并写入缓存供下次复用。仅客户端可用。
 */
export async function resolveCachedIconBlob(url: string): Promise<Blob | undefined> {
  if (!import.meta.client || !url) return undefined
  const key = iconCacheKey(url)
  const cached = await getCachedIconBlob(key)
  if (cached) return cached

  const inflight = iconFetchInflight.get(key)
  if (inflight) return inflight

  const task = (async () => {
    try {
      const resp = await fetch(url)
      if (!resp.ok) return undefined
      const blob = await resp.blob()
      void putCachedIconBlob(key, blob)
      return blob
    } catch {
      return undefined
    } finally {
      iconFetchInflight.delete(key)
    }
  })()
  iconFetchInflight.set(key, task)
  return task
}

