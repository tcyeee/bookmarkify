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
 * 这个网址会被抓取吗？**只有域名会。**
 *
 * `localhost:5173`、`127.0.0.1:5000`、`192.168.0.73:8192`、乃至公网裸 IP `47.97.71.143:8001`
 * 一律不抓：前三者只在用户自己的网络里存在，服务器去连要么连到抓取容器自己、要么直接超时；
 * 公网裸 IP 则是别人内网服务暴露出来的端口，抓回来只会是一个登录页。这三类都不是"网站"，
 * 抓取的产物（标题/图标/OG 图）对书签没有任何价值。
 *
 * 这条规则的权威在后端（`ScrapeTargetGuard`，拒绝时报 E309），scrapper 侧还有第三道门。
 * 前端这份**不是校验**——这类网址仍然可以正常收藏，只是不会有抓取结果。它存在的意义是
 * 提前把"这条不会有标题和图标"说出来，否则用户加完一个 `localhost:5173` 只会看到一个
 * 光秃秃的圆圈，以为是加错了或者系统坏了。
 */
export function isScrapableUrl(input?: string | null): boolean {
  const raw = (input || '').trim()
  if (!raw) return false
  let hostname: string
  try {
    hostname = new URL(externalHref(raw)).hostname
  } catch {
    return false
  }
  // URL.hostname 对 IPv6 返回带方括号的字面量（`[::1]`）
  const host = hostname.replace(/^\[|\]$/g, '').replace(/\.$/, '').toLowerCase()
  if (!host) return false
  if (host === 'localhost' || host.endsWith('.localhost')) return false
  // IPv4 字面量（含公网 IP）
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) return false
  // IPv6 字面量：含冒号且只由十六进制字符与冒号组成
  if (host.includes(':') && /^[0-9a-f:]+$/.test(host)) return false
  return host.includes('.')
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

// ── 图标主色调 ──
// 图标卡片的底色取自图标自身，而不是一律铺白：站点图标绝大多数是透明底 + 一枚彩色标志，
// 铺白之后整面桌面就是一排白方块，要找的那个很难一眼扫到；铺上该站自己的色系就认得出来。
//
// 底色用的是「主色调的色相 + 固定的高亮度」，不是主色原色。**原色铺满会让标志消失**——
// 标志本身就是那个颜色，深蓝底上的深蓝 logo、黑底上的黑猫头（GitHub）都会糊成一块。
// 只保留色相、把亮度按下面这个常量归一，既有辨识度又保证对比。想改成原色铺满只需改
// logoSurfaceColorOf 一处。
const LOGO_SURFACE_LIGHTNESS = 92
// 饱和度夹取区间：太低看不出是什么色，太高在浅色底上会发荧光
const LOGO_SURFACE_SATURATION_MIN = 30
const LOGO_SURFACE_SATURATION_MAX = 72
// 主色本身就是灰阶（纯黑白线稿图标，GitHub 的猫头是典型）时不硬凑一个色相出来，铺中性浅灰。
// 阈值取 15 而不是 0：近黑近白的像素在 RGB 里往往带一点残留色偏（GitHub 那个黑是 #24292f，
// 算出来有 13% 饱和度），照色相铺开会给一个纯黑图标配上淡蓝卡片
const LOGO_SURFACE_NEUTRAL_SATURATION = 15
// 采样画布边长：只做颜色统计不看细节，32×32 一次 getImageData 才 4KB
const LOGO_SAMPLE_SIZE = 32
// 直方图量化位数：每通道保留高 5 位（32 档），把同一枚标志上的抗锯齿渐变归到同一个桶里
const LOGO_QUANTIZE_SHIFT = 3

// 主色调按图标缓存 key 记忆（同一张图在列表行 + 置顶区可能同时渲染多次）。
// 只放内存不落盘：字节本身已经在 IndexedDB 里，重算一次也就一次 32×32 的 getImageData。
const logoColorCache = new Map<string, string | null>()
const logoColorInflight = new Map<string, Promise<string | null>>()

/** 把 blob 解码成 <img>。不用 createImageBitmap：它对 SVG blob 的支持各家浏览器不一致。 */
function decodeIconImage(blob: Blob): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const objectUrl = URL.createObjectURL(blob)
    const img = new Image()
    // 没有固有尺寸的 SVG（只有 viewBox、不写 width/height）在 naturalWidth 上是 0，
    // 直接 drawImage 画不出任何东西；给它一个显式视口尺寸才有像素可采
    img.width = LOGO_SAMPLE_SIZE
    img.height = LOGO_SAMPLE_SIZE
    img.onload = () => {
      URL.revokeObjectURL(objectUrl)
      resolve(img)
    }
    img.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      resolve(null)
    }
    img.src = objectUrl
  })
}

/** 把图标缩到 LOGO_SAMPLE_SIZE 见方后取出 RGBA 像素。 */
function sampleIconPixels(img: HTMLImageElement): Uint8ClampedArray | null {
  try {
    const canvas = document.createElement('canvas')
    canvas.width = LOGO_SAMPLE_SIZE
    canvas.height = LOGO_SAMPLE_SIZE
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    if (!ctx) return null
    ctx.drawImage(img, 0, 0, LOGO_SAMPLE_SIZE, LOGO_SAMPLE_SIZE)
    return ctx.getImageData(0, 0, LOGO_SAMPLE_SIZE, LOGO_SAMPLE_SIZE).data
  } catch {
    // 跨域图片会污染画布，getImageData 抛 SecurityError。调用方只从本地 blob 取样，
    // 正常不会走到这里；真走到了就当作「取不出主色」退回默认白底
    return null
  }
}

/**
 * 从像素里挑出主色：按量化桶统计出现次数最多的颜色，再用桶内像素的真实均值还原精度。
 *
 * 两轮统计。第一轮**只看有颜色的像素**——跳过透明、近白、近黑与灰阶，因为图标的白底/黑字
 * 往往占了绝大多数面积，按面积直接投票的话几乎每个图标的主色都是白色或黑色，等于没算。
 * 一轮下来一个像素都没有的（纯灰阶线稿图标）再放开限制统计一次，否则这类图标拿不到底色。
 */
function dominantRgbOf(data: Uint8ClampedArray): [number, number, number] | null {
  const tally = (colorfulOnly: boolean) => {
    const buckets = new Map<number, { count: number; r: number; g: number; b: number }>()
    for (let i = 0; i < data.length; i += 4) {
      const r = data[i]!
      const g = data[i + 1]!
      const b = data[i + 2]!
      const a = data[i + 3]!
      if (a < 128) continue
      if (colorfulOnly) {
        const max = Math.max(r, g, b)
        const min = Math.min(r, g, b)
        if (max > 240 && min > 240) continue // 近白
        if (max < 24) continue // 近黑
        if (max - min < 24) continue // 灰阶
      }
      const key =
        ((r >> LOGO_QUANTIZE_SHIFT) << 10) | ((g >> LOGO_QUANTIZE_SHIFT) << 5) | (b >> LOGO_QUANTIZE_SHIFT)
      const bucket = buckets.get(key)
      if (bucket) {
        bucket.count += 1
        bucket.r += r
        bucket.g += g
        bucket.b += b
      } else {
        buckets.set(key, { count: 1, r, g, b })
      }
    }
    let best: { count: number; r: number; g: number; b: number } | null = null
    for (const bucket of buckets.values()) {
      if (!best || bucket.count > best.count) best = bucket
    }
    if (!best) return null
    return [
      Math.round(best.r / best.count),
      Math.round(best.g / best.count),
      Math.round(best.b / best.count),
    ] as [number, number, number]
  }
  return tally(true) ?? tally(false)
}

/** RGB → HSL，只用到色相与饱和度，返回值单位是度与百分比。 */
function rgbToHsl(r: number, g: number, b: number): [number, number, number] {
  const rn = r / 255
  const gn = g / 255
  const bn = b / 255
  const max = Math.max(rn, gn, bn)
  const min = Math.min(rn, gn, bn)
  const l = (max + min) / 2
  const d = max - min
  if (d === 0) return [0, 0, l * 100]
  const s = d / (1 - Math.abs(2 * l - 1))
  let h: number
  if (max === rn) h = ((gn - bn) / d) % 6
  else if (max === gn) h = (bn - rn) / d + 2
  else h = (rn - gn) / d + 4
  h *= 60
  if (h < 0) h += 360
  return [h, s * 100, l * 100]
}

/** 主色 RGB → 可直接用作背景的 CSS 颜色（同色相的浅色底，见文件上方的说明）。 */
export function logoSurfaceColorOf(rgb: [number, number, number]): string {
  const [h, s] = rgbToHsl(rgb[0], rgb[1], rgb[2])
  if (s < LOGO_SURFACE_NEUTRAL_SATURATION) return `hsl(0 0% ${LOGO_SURFACE_LIGHTNESS + 2}%)`
  const sat = Math.min(LOGO_SURFACE_SATURATION_MAX, Math.max(LOGO_SURFACE_SATURATION_MIN, s))
  return `hsl(${Math.round(h)} ${Math.round(sat)}% ${LOGO_SURFACE_LIGHTNESS}%)`
}

/**
 * 取某张图标的底色。`url` 只用来算缓存 key（与图标字节缓存同一套，签名变了照样命中），
 * 像素一律从 `blob` 取——直连的签名地址是跨域的，画进 canvas 会污染画布。
 * 算不出来时返回 null，调用方退回默认底色。仅客户端可用。
 */
export async function resolveLogoSurfaceColor(url: string, blob: Blob): Promise<string | null> {
  if (!import.meta.client || !url) return null
  const key = iconCacheKey(url)
  const cached = logoColorCache.get(key)
  if (cached !== undefined) return cached

  const inflight = logoColorInflight.get(key)
  if (inflight) return inflight

  const task = (async () => {
    try {
      const img = await decodeIconImage(blob)
      const pixels = img && sampleIconPixels(img)
      const rgb = pixels && dominantRgbOf(pixels)
      const color = rgb ? logoSurfaceColorOf(rgb) : null
      logoColorCache.set(key, color)
      return color
    } catch {
      logoColorCache.set(key, null)
      return null
    } finally {
      logoColorInflight.delete(key)
    }
  })()
  logoColorInflight.set(key, task)
  return task
}

