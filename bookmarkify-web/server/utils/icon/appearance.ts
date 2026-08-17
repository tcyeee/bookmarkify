/**
 * 由图标自身的像素推出它该**怎么摆**：底色，以及要不要留白。
 *
 * 两件事共用 `./pixels` 的同一次解码 —— 那是这条链路上唯一真正花时间的一步，各解一次图
 * 等于白付一倍。缓存的也是这个合成结果，而不是其中某一个值。
 */
import { ICON_SAMPLE_SIZE, iconPixelsOf } from './pixels'
import { iconCacheKey } from './cache'

// ── 底色 ──
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
// 直方图量化位数：每通道保留高 5 位（32 档），把同一枚标志上的抗锯齿渐变归到同一个桶里
const LOGO_QUANTIZE_SHIFT = 3

// ── 留白 ──
// 有些图标是**透明底 + 图形直接顶到边**。铺满整个卡片时，那枚图形就贴着圆角边缘，
// 看起来像是被裁掉了一圈；而同一屏里那些自带留白的图标是缩在中间的，两种摆法混在一起
// 尤其难看。判据要两个条件同时成立：
//
//   ① 背景确实是透明的 —— 不透明的方形图标（自带色块底的 PNG favicon）本来就该铺满，
//      给它加 padding 会凭空缩小一圈；
//   ② 图形确实顶到了边 —— 自带留白的图标不需要我们再补。
//
// 两条都满足才补一档固定留白。这是个**保守**的判断：漏判只是维持现状，误判会让一批本来
// 正常的图标集体缩水。
const ICON_ALPHA_THRESHOLD = 128
// 视为「透明底」的门槛：边缘一圈里至少这么多比例的像素是透明的
const ICON_TRANSPARENT_EDGE_RATIO = 0.6
// 视为「顶到边」的门槛：图形包围盒占到画布这么多比例
const ICON_FULL_BLEED_RATIO = 0.96
// 补多少：占卡片边长的比例。8% 对 56px 的磁贴约 4.5px，刚好让图形从圆角边缘退开一点，
// 又不至于把本来就不大的图标缩得更小
export const ICON_FULL_BLEED_PADDING = 0.08

// ── 去掉图标自带的边距 ──
// 上面那条规则的**反方向**，也是同一条设定的另一半：**外缘自带颜色的图标不该有留白**。
// 有一类图标本身就是一枚「卡片」—— 实心圆角方块 + 图形，即 iOS/Android 那种 app icon
// （小红书的 manifest 512 图标就是典型：红色圆角方块，四周还烤进了 7.8% 的透明边距）。
// 它铺进我们的圆角卡片时，那圈透明边距会把红方块缩在中间，露出一圈底色 —— 看起来正是
// 「加了 padding」，只不过这一圈不是我们加的，是图片字节里本来就有的。视觉上是卡片套卡片，
// 比图形贴边更难看，而 padding 判据（要求包围盒 ≥96%）永远管不到它。
//
// 判据同样要几条同时成立，且刻意比补留白那条更严 —— 放大是**不可逆的观感改动**：
//
//   ① 图形自己填满了它的包围盒 —— 这是「外缘带颜色」的可计算版本。实心圆角方块的填充率
//      约 0.93，而线稿、字标、圆形徽标都在 0.8 以下（圆形理论值 π/4 ≈ 0.785），落不进来；
//      圆形徽标缩在卡片中间本来就不难看，它不是「卡片套卡片」。
//   ② 边距在一个正常范围内 —— 太小（≤6%）不值得动，太大说明那不是边距而是别的构图。
const ICON_SELF_TILE_FILL_RATIO = 0.85
const ICON_SELF_TILE_MAX_EXTENT = 0.94
const ICON_SELF_TILE_MIN_EXTENT = 0.7

/** 一张图标的外观：底色 + 留白比例 + 放大倍数。三者出自同一次像素采样 */
export interface IconAppearance {
  /** 同色相的浅色底；算不出来（灰阶图标除外）时为 null，调用方用默认白底 */
  surfaceColor: string | null
  /** 该给这张图补多少留白，占卡片边长的比例。0 = 铺满 */
  padding: number
  /** 该把这张图放大多少倍，用来顶掉它自带的透明边距。1 = 原样 */
  scale: number
}

const EMPTY_APPEARANCE: IconAppearance = { surfaceColor: null, padding: 0, scale: 1 }

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
 * 这张图该**怎么摆进卡片**：补一圈留白，还是放大顶掉它自带的边距。
 *
 * 两个判断读的是同一批量：最外一圈像素的透明比例（不透明说明它自带底色，本就该铺满）、
 * alpha 达标像素的包围盒、以及包围盒内的填充率。**两者互斥**：补留白要求包围盒 ≥96%
 * （图形顶到了边），放大要求 ≤94%（图形自带边距），中间那一档两边都不做。
 *
 * `w`/`h` 是相对**图片自身**的比例，而图片是被拉伸成正方形采样的（见 pixels.ts）——
 * 对非方图，两轴的边距比例各自成立，取 max 等于先顶掉边距较小的那一轴，宁可少放大。
 */
function framingOf(data: Uint8ClampedArray): { padding: number; scale: number } {
  const none = { padding: 0, scale: 1 }
  const n = ICON_SAMPLE_SIZE
  const alphaAt = (x: number, y: number) => data[(y * n + x) * 4 + 3] ?? 0

  // ① 边缘一圈是不是透明的。不透明 = 图标自带色块底，铺满即可，两件事都不用做
  let edge = 0
  let edgeTransparent = 0
  for (let i = 0; i < n; i++) {
    for (const [x, y] of [[i, 0], [i, n - 1], [0, i], [n - 1, i]] as const) {
      edge++
      if (alphaAt(x, y) < ICON_ALPHA_THRESHOLD) edgeTransparent++
    }
  }
  if (edge === 0 || edgeTransparent / edge < ICON_TRANSPARENT_EDGE_RATIO) return none

  // ② 图形的包围盒有多大，盒子里又填了多满
  let minX = n
  let minY = n
  let maxX = -1
  let maxY = -1
  let opaque = 0
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (alphaAt(x, y) < ICON_ALPHA_THRESHOLD) continue
      opaque++
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }
  }
  // 整张全透明：没有图形可言，两件事都无从谈起
  if (maxX < 0) return none
  const w = (maxX - minX + 1) / n
  const h = (maxY - minY + 1) / n
  const extent = Math.max(w, h)

  // 顶到边：补一档固定留白
  if (extent >= ICON_FULL_BLEED_RATIO) return { padding: ICON_FULL_BLEED_PADDING, scale: 1 }

  // 自带边距 + 图形填满自己的包围盒（实心色块，即「外缘带颜色」）：放大到刚好铺满
  const fill = opaque / ((maxX - minX + 1) * (maxY - minY + 1))
  if (extent <= ICON_SELF_TILE_MAX_EXTENT && extent >= ICON_SELF_TILE_MIN_EXTENT && fill >= ICON_SELF_TILE_FILL_RATIO) {
    return { padding: 0, scale: 1 / extent }
  }

  return none
}

// 外观按图标缓存 key 记忆（同一张图在列表行 + 置顶区可能同时渲染多次）。
// 只放内存不落盘：字节本身已经在 IndexedDB 里，重算一次也就一次 getImageData。
// **缓存的是合成后的 IconAppearance 而不是其中某一个值** —— 分开缓存就等于分开解码。
const appearanceCache = new Map<string, IconAppearance>()
const appearanceInflight = new Map<string, Promise<IconAppearance>>()

/**
 * 取某张图标的外观。`url` 只用来算缓存 key（与图标字节缓存同一套，签名变了照样命中），
 * 像素一律从 `blob` 取——直连的签名地址是跨域的，画进 canvas 会污染画布。
 * 算不出来时返回默认外观（白底、不留白）。仅客户端可用。
 */
export async function resolveIconAppearance(url: string, blob: Blob): Promise<IconAppearance> {
  if (!import.meta.client || !url) return EMPTY_APPEARANCE
  const key = iconCacheKey(url)
  const cached = appearanceCache.get(key)
  if (cached !== undefined) return cached

  const inflight = appearanceInflight.get(key)
  if (inflight) return inflight

  const task = (async () => {
    let appearance = EMPTY_APPEARANCE
    try {
      const pixels = await iconPixelsOf(blob)
      if (pixels) {
        const rgb = dominantRgbOf(pixels)
        appearance = { surfaceColor: rgb ? logoSurfaceColorOf(rgb) : null, ...framingOf(pixels) }
      }
    } catch {
      // 保持默认外观
    } finally {
      appearanceInflight.delete(key)
    }
    appearanceCache.set(key, appearance)
    return appearance
  })()
  appearanceInflight.set(key, task)
  return task
}
