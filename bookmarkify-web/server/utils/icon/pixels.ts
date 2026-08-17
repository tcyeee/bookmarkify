/**
 * 把一张图标解码成一小块 RGBA 像素。**只负责拿到像素，不解释像素。**
 *
 * 单独一层是因为它有两个消费者（主色、留白判定），而解码 + drawImage 是这条链路上唯一
 * 真正花时间的一步 —— 两边各解一次图就等于白付一倍。`./appearance` 保证只调用一次，
 * 把同一份 ImageData 喂给两个判断。
 */

/**
 * 采样画布边长。
 *
 * 32 曾经够用（只做颜色统计），但留白判定要看「图形有没有顶到边」，32px 下一圈 1px 的边框
 * 就占 3%，包围盒的分辨率太粗。64 让边缘判定有 ~1.5% 的精度，代价是一次 getImageData
 * 从 4KB 变成 16KB —— 在一次图片解码面前可以忽略。
 */
export const ICON_SAMPLE_SIZE = 64

/** 把 blob 解码成 <img>。不用 createImageBitmap：它对 SVG blob 的支持各家浏览器不一致。 */
function decodeIconImage(blob: Blob): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const objectUrl = URL.createObjectURL(blob)
    const img = new Image()
    // 没有固有尺寸的 SVG（只有 viewBox、不写 width/height）在 naturalWidth 上是 0，
    // 直接 drawImage 画不出任何东西；给它一个显式视口尺寸才有像素可采
    img.width = ICON_SAMPLE_SIZE
    img.height = ICON_SAMPLE_SIZE
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

/** 把图标缩到 ICON_SAMPLE_SIZE 见方后取出 RGBA 像素。 */
function sampleIconPixels(img: HTMLImageElement): Uint8ClampedArray | null {
  try {
    const canvas = document.createElement('canvas')
    canvas.width = ICON_SAMPLE_SIZE
    canvas.height = ICON_SAMPLE_SIZE
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    if (!ctx) return null
    ctx.drawImage(img, 0, 0, ICON_SAMPLE_SIZE, ICON_SAMPLE_SIZE)
    return ctx.getImageData(0, 0, ICON_SAMPLE_SIZE, ICON_SAMPLE_SIZE).data
  } catch {
    // 跨域图片会污染画布，getImageData 抛 SecurityError。调用方只从本地 blob 取样，
    // 正常不会走到这里；真走到了就当作「取不出像素」，外观退回默认值
    return null
  }
}

/** blob → 一块 ICON_SAMPLE_SIZE² 的 RGBA 像素；解不出来返回 null。仅客户端可用。 */
export async function iconPixelsOf(blob: Blob): Promise<Uint8ClampedArray | null> {
  if (!import.meta.client) return null
  const img = await decodeIconImage(blob)
  return img ? sampleIconPixels(img) : null
}
