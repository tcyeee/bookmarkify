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
