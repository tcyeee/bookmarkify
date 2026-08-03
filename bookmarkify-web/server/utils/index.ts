import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'
import { nanoid } from 'nanoid'
import { createAvatar } from '@dicebear/core'
import { adventurerNeutral } from '@dicebear/collection'

export { md5 } from 'js-md5'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(...inputs))
}

export function randomId() {
  return nanoid().slice(0, 8)
}

// 后端 bookmark.url_full 存的是「用户给的原始网址」，用户手输时常常省略协议（github.com/tcyeee）。
// 这种字符串直接塞进 href / window.open 会被浏览器当成站内相对路径，点开变成
// https://bookmarkify.cc/github.com/tcyeee。凡是把书签地址当跳转目标用的地方都要过这一层。
const HAS_SCHEME_RE = /^[a-z][a-z0-9+.-]*:(?!\d)/i
// 内网/本地地址：这类站点基本没有证书，补 https 必定连不上，补 http 才有意义
const LOCAL_HOST_RE = /^(localhost|127\.\d+\.\d+\.\d+|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)(:|\/|$)/i

// 允许出现在 href / window.open 里的协议白名单。**必须是白名单而不是黑名单**：
// 后端的导入链路刻意保留了 javascript: 小书签等原始写法（见 BookmarkEntity.normalizeImportedUrl），
// 而 /share/[code] 页渲染的是**别人**的书签列表——放行 javascript:/data: 等同于把
// 存储型 XSS 的执行权交给任何一个分享者。名单外的一律换成 about:blank：
// 返回空串会让 <a href=""> 点击后重载当前页，about:blank 才是「这里什么都没有」的诚实表达。
// 顺带一提，javascript: 书签本来也只有拖进浏览器书签栏才有意义，
// 从站内 <a> 点开只会在 bookmarkify.cc 自己的上下文里执行——放行它一点功能都换不来。
const SAFE_SCHEMES = new Set(['http:', 'https:', 'mailto:', 'tel:'])
const BLOCKED_HREF = 'about:blank'

/** 把书签地址补全为可跳转的绝对地址：安全协议或协议相对地址原样返回，无协议的补默认协议，其余一律拦掉。 */
export function externalHref(url?: string | null): string {
  const raw = (url || '').trim()
  if (!raw) return ''
  // 协议相对地址（//host/path）继承当前页的 http(s)，不存在协议注入问题
  if (raw.startsWith('//')) return raw
  // 冒号后紧跟数字的排除在外：localhost:3000 是「主机+端口」而不是 scheme
  if (HAS_SCHEME_RE.test(raw)) {
    const scheme = raw.slice(0, raw.indexOf(':') + 1).toLowerCase()
    return SAFE_SCHEMES.has(scheme) ? raw : BLOCKED_HREF
  }
  return `${LOCAL_HOST_RE.test(raw) ? 'http' : 'https'}://${raw}`
}

// 对应后端 bookmark_user_link.url_full 的 varchar(1000)（见 deploy/schema.sql）。
// 前端先拦一道只是为了即时反馈，真正的约束在 WebsiteParser.urlWrapper 里。
const MAX_URL_LENGTH = 1000

/**
 * 判断用户输入的字符串是不是一个能收藏的 http(s) 网址。
 *
 * 用 `new URL()` 而不是手写正则。这里原本是一条自造的正则，把大量合法网址判成非法并**硬拦截**
 * （「添加」按钮置灰）：中文维基条目（路径含非 ASCII）、Gmail 邮件链接（fragment 含 `/`）、
 * 带 URL 参数的搜索结果页（query 含 `:` `/`）、`localhost:3000`、含逗号或括号的路径，全都加不进来。
 * 而后端用的是 `java.net.URL`，这些它全都能解析——等于前端把后端能处理的输入挡在了门外。
 * URL 语法的唯一可靠实现是运行时内置的那个，不该有第二套。
 */
export function isBookmarkableUrl(input?: string | null): boolean {
  const raw = (input || '').trim()
  if (!raw || raw.length > MAX_URL_LENGTH) return false
  // 网址内部不允许出现空白：`new URL` 会把空格转义成 %20 从而放行「hello world」这种明显不是网址的输入
  if (/\s/.test(raw)) return false

  let parsed: URL
  try {
    parsed = new URL(externalHref(raw))
  } catch {
    return false
  }
  // 只收 http/https：javascript: 小书签、about: 页面这类后端抓不了也存不进 canonical 体系
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false
  if (!parsed.hostname) return false

  // 用户显式写了协议就认他的（内网主机名往往没有点），否则要求主机名像个真域名，
  // 否则「记事本」「todo」这类随手输入会被补成 https://todo 而误判为合法网址
  const hasExplicitScheme = /^https?:\/\//i.test(raw)
  if (!hasExplicitScheme && !parsed.hostname.includes('.') && parsed.hostname !== 'localhost') return false
  // `https://.` / `https://a..b` 这类畸形主机名 new URL 是接受的，这里补一道
  if (parsed.hostname.includes('..') || parsed.hostname.startsWith('.') || parsed.hostname.endsWith('.')) return false

  return true
}

/**
 * 把网址归一成一个可比较的键，用于**前端侧**的"这条我已经收藏过了"判断。
 *
 * 这不是后端那套 canonical 四元组的复刻，也不该是——真正的判重永远在后端（`assertNotAlreadyLinked`，
 * 按 canonical bookmarkId 比对）。这里只是省掉一次注定失败的往返，所以规则刻意取**更保守**的一档：
 * 只做「统一大小写 + 去掉结尾斜杠 + 查询参数按 key 排序」，不剥离追踪参数。后端会剥，也就是说
 * 后端认定相同的集合严格包含这里认定相同的集合 —— 于是这里只会漏判（交给后端兜住），不会误判把
 * 用户明明没收藏过的网址拦下来。
 *
 * 无法解析或不是 http(s) 时返回 null，调用方据此跳过本地判断。
 */
export function canonicalUrlKey(input?: string | null): string | null {
  const href = externalHref(input)
  if (!href || href === BLOCKED_HREF) return null
  let parsed: URL
  try {
    parsed = new URL(href)
  } catch {
    return null
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null
  // 只按 key 排序，同名参数的相对顺序保持不变（?a=1&a=2 与 ?a=2&a=1 是两个不同的地址）
  const query = [...parsed.searchParams.entries()]
    .map(([k, v], i) => [k, v, i] as const)
    .sort((x, y) => x[0].localeCompare(y[0]) || x[2] - y[2])
    .map(([k, v]) => `${k}=${v}`)
    .join('&')
  const path = parsed.pathname.replace(/\/+$/, '') || '/'
  return `${parsed.host.toLowerCase()}${path}${query ? `?${query}` : ''}`
}

/**
 * 用 DiceBear adventurer-neutral 按 seed 生成头像，返回可上传的 SVG File 与可预览的 dataUri。
 * 仅客户端调用（依赖 File / Blob）。相同 seed 结果可复现（uid 用于默认头像，nanoid 用于随机头像）。
 */
export function createDicebearAvatar(seed: string): { file: File; dataUri: string } {
  const svg = createAvatar(adventurerNeutral, { seed }).toString()
  const file = new File([svg], 'avatar.svg', { type: 'image/svg+xml' })
  // 自行拼接 data URI：DiceBear v9 的 toDataUri() 产出 `;utf8,` 这种非法 media-type 参数，
  // 会被浏览器当作 text/plain，导致 <img> 无法渲染。改用标准的 charset=utf-8 形式。
  const dataUri = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
  return { file, dataUri }
}

/**
 * 用 DiceBear adventurer-neutral 按 seed 生成确定性头像 SVG File。
 * seed 一般传用户 uid，保证可复现。
 */
export function generateDefaultAvatarFile(seed: string): File {
  return createDicebearAvatar(seed).file
}

const NICK_ADJ = ['悠闲', '快乐', '迷糊', '勇敢', '安静', '热情', '慵懒', '机智', '温柔', '倔强', '神秘', '调皮', '优雅', '呆萌', '潇洒', '元气']
const NICK_NOUN = ['小熊猫', '柯基', '水豚', '旅人', '喵星人', '北极熊', '刺猬', '海獭', '狐狸', '考拉', '企鹅', '章鱼', '仓鼠', '麋鹿', '鲸鱼', '兔子']

/** 生成一个「形容词 + 的 + 动物」风格的随机昵称，长度可控（≤ 20）。 */
export function randomNickName(): string {
  // 两个词表都是非空常量，随机下标必然命中；`?? arr[0]!` 只是给类型系统一个交代
  const pick = <T>(arr: T[]): T => arr[Math.floor(Math.random() * arr.length)] ?? arr[0]!
  return `${pick(NICK_ADJ)}的${pick(NICK_NOUN)}`
}

// ── 书签图标本地持久缓存 ──
// 阿里云 OSS 图片处理按次计费，OssUtils.signAsset 签出的地址只在同一时间窗口内保持字节不变
// （见后端 signWithResize），窗口一过签名刷新、URL 跟着变，浏览器 HTTP 缓存也随之失效——图标
// 又要真刷一次 OSS。这里用 IndexedDB 把图标字节按「路径 + 缩放参数」（剥离签名相关的易变
// query 参数）持久缓存在本地：签名怎么变，缓存怎么命中，从根本上减少重复的 OSS 请求，
// 命中时也无需等网络往返，首屏图标秒开。

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

