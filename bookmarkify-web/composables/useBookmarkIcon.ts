import { computed, onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { BookmarkLinkType, type BookmarkShow } from '@typing'
import { resolveCachedIconBlob } from '@utils/icon/cache'
import { resolveIconAppearance } from '@utils/icon/appearance'

/**
 * 「这个书签的图标该长什么样」—— 把判断从 `BookmarkLogo.vue` 里摘出来，组件只剩渲染。
 *
 * 服务端已经按展示模式选好了唯一一张图（`AssetRolePolicy`），前端不再在多个图位之间取舍；
 * 这里做的是服务端做不了的那部分：本地字节缓存、以及**由字节本身推出的外观**（底色、留白）。
 * 后者必须在客户端算，因为服务端手里根本没有图片字节 —— 它只从 scrapper 收到宽高和哈希。
 */

/** 该渲染哪一种 —— 四选一，顺序即优先级 */
export type BookmarkIconKind =
  /** 本地/IP 书签：后端不抓取，统一显示圆点 */
  | 'plain'
  /** 正常显示图片 */
  | 'image'
  /** 首字母色块：该站没有够格的图，硬拉伸只会更难看 */
  | 'monogram'

/** 磁贴形态（置顶区）用 tileLogo，其余用 logo。见 BookmarkShow.tileLogo */
export type BookmarkIconVariant = 'list' | 'tile'

export function useBookmarkIcon(
  value: Ref<BookmarkShow>,
  variant: Ref<BookmarkIconVariant>,
) {
  const iconError = ref(false)

  /**
   * 用哪一份解析结果。
   *
   * `tileLogo` 是后端 2026-08-17 才补的字段，老版本不下发 —— 而 web 是静态站，用户标签页
   * 可能开着好几天。`??` 回落到 `logo` 保证那种组合下只是回到旧行为（略糊），而不是没有图标。
   */
  const resolved = computed(() =>
    variant.value === 'tile' ? (value.value.tileLogo ?? value.value.logo) : value.value.logo,
  )

  // 本地(localhost/127.0.0.1)或纯 IP：后端不抓取网站信息，统一展示 mdi 圆圈图标
  const isPlainCircle = computed(
    () => value.value.linkType === BookmarkLinkType.LOCAL || value.value.linkType === BookmarkLinkType.IP,
  )

  // 网站不可访问：图标变灰并叠加断网标识
  const isInactive = computed(() => value.value?.isActivity === false)

  // monogram 为 true 表示"该站没有够格的图"，此时不渲染图片
  const imageUrl = computed(() => (resolved.value?.monogram ? '' : resolved.value?.url || ''))

  // 实际渲染用的地址：优先走本地持久缓存（命中则是 objectURL，零网络请求），
  // 缓存未命中/解析失败时退回签名地址直连，保证展示不受影响
  const displaySrc = ref('')
  let displayObjectUrl: string | null = null

  // 由图标自身字节算出的外观（底色 + 留白）。两者出自同一次像素采样，见 icon/appearance
  const surfaceColor = ref<string | null>(null)
  const padding = ref(0)

  function releaseDisplayObjectUrl() {
    if (displayObjectUrl) {
      URL.revokeObjectURL(displayObjectUrl)
      displayObjectUrl = null
    }
  }

  watch(
    imageUrl,
    async (url) => {
      releaseDisplayObjectUrl()
      surfaceColor.value = null
      padding.value = 0
      if (!url) {
        displaySrc.value = ''
        return
      }
      const blob = await resolveCachedIconBlob(url)
      // 异步期间图片可能已经切换（换书签/换图），丢弃过期结果
      if (imageUrl.value !== url) return
      if (blob) {
        displayObjectUrl = URL.createObjectURL(blob)
        displaySrc.value = displayObjectUrl
        // 外观只从本地字节算：缓存未命中时退回的是跨域签名地址，画进 canvas 会污染画布，
        // getImageData 直接抛 SecurityError。取不到 blob 的那条分支索性不算，用默认外观
        const appearance = await resolveIconAppearance(url, blob)
        if (imageUrl.value === url) {
          surfaceColor.value = appearance.surfaceColor
          padding.value = appearance.padding
        }
      } else {
        displaySrc.value = url
      }
    },
    { immediate: true },
  )

  onBeforeUnmount(releaseDisplayObjectUrl)

  const kind = computed<BookmarkIconKind>(() => {
    if (isPlainCircle.value) return 'plain'
    if (displaySrc.value && !iconError.value) return 'image'
    return 'monogram'
  })

  // 取标题/域名首字符；中文直接用该字，英文用大写字母
  const monogramChar = computed(() => {
    const src = value.value.title || value.value.urlBase || value.value.urlFull || '?'
    const ch = src.replace(/^https?:\/\//i, '').trim().charAt(0)
    return (ch || '?').toUpperCase()
  })

  // 由标题散列出稳定的色相：同一个书签每次渲染颜色一致，不同书签易于区分
  const monogramHue = computed(() => {
    const src = value.value.title || value.value.urlBase || '?'
    let hash = 0
    for (let i = 0; i < src.length; i++) hash = (hash * 31 + src.charCodeAt(i)) >>> 0
    return hash % 360
  })

  // 换书签或换图时重置错误状态，让新图有机会加载
  watch(
    () => [value.value.bookmarkId, resolved.value?.url],
    () => {
      iconError.value = false
    },
  )

  return {
    kind,
    isInactive,
    displaySrc,
    surfaceColor,
    padding,
    monogramChar,
    monogramHue,
    onIconError: () => {
      iconError.value = true
    },
  }
}
