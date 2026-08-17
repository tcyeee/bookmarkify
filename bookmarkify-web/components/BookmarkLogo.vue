<template>
  <!-- 外层容器：卡片圆角。shrink-0 必需——多处调用把本组件放进 flex 行里与超长标题同排，
       没有它时卡片会被 flex 横向压扁成长方形（overflow-hidden 再把图标裁掉） -->
  <div class="relative shrink-0 rounded-[22%] bg-gray-100 center overflow-hidden">
    <!-- 内层 Logo：默认白底，取到图标主色后换成同色系的浅色底；不可访问时整体变灰。
         底色是异步算出来的（要先拿到图标字节），transition 让它淡入而不是硬跳一下 -->
    <div
      class="bg-white flex justify-center items-center transition-colors duration-300"
      :class="{ 'inactive-logo': isInactive }"
      :style="[logoSizeStyle, logoStyle]">
      <!-- 本地/IP 类型书签：后端不抓取信息，用与「不可访问」同色的灰底 + 白色圆点图标 -->
      <Icon v-if="kind === 'plain'" icon="mdi:dots-circle" class="shrink-0 text-white" :style="glyphStyle" />
      <!-- 服务端已按展示模式选好唯一一张图，前端不再在多个图位之间取舍。
           src 走本地持久缓存解析（见 icon/cache），命中时零网络请求 -->
      <img
        v-else-if="kind === 'image'"
        :key="displaySrc"
        :style="imageStyle"
        :src="displaySrc"
        alt=""
        draggable="false"
        @error="onIconError"
      />
      <!-- 首字母色块：该站没有够格的图（monogram），硬把 32px 的 favicon 拉到 72px 只会更难看 -->
      <div v-else class="monogram" :style="monogramStyle">{{ monogramChar }}</div>
    </div>

    <!-- 不可访问：整体覆盖一层灰色蒙版，居中叠加白色断网标识 -->
    <div v-if="isInactive" class="inactive-mask" :style="{ backgroundColor: INACTIVE_GRAY }" aria-label="无法访问">
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        class="shrink-0"
        :style="glyphStyle">
        <path d="M1 1l22 22" />
        <path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55" />
        <path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39" />
        <path d="M10.71 5.05A16 16 0 0 1 22.58 9" />
        <path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88" />
        <path d="M8.53 16.11a6 6 0 0 1 6.95 0" />
        <line x1="12" y1="20" x2="12.01" y2="20" />
      </svg>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, toRef } from 'vue'
import { Icon } from '@iconify/vue'
import type { BookmarkShow } from '@typing'
import { useBookmarkIcon, type BookmarkIconVariant } from '../composables/useBookmarkIcon'

defineOptions({ name: 'BookmarkLogo' })

// 两档标准尺寸，对应桌面上书签图标仅有的两种形态：
//   S —— 文件夹卡片里的书签行（小图 + 完整标题），后端 LIST 模式
//   M —— 置顶区磁贴（大图 + 一行短文案），后端 TILE 模式
// 这两个字母是**这个组件对外的尺寸词汇**，业务里新增用法一律用它们，不要再写像素数：
// 服务端本来就只按 LIST / TILE 两种模式选图和缩放（AssetRolePolicy），前端散落一堆
// 20/28/36/56 的魔法数字，等于在服务端只认两档的地方假装有无级尺寸。
// 仍然接受任意像素值，是因为设置页/分享页几个列表当年就是按各自的行高定的，
// 统一到两档会改动那几屏的观感，那是另一件事。
const SIZE_PRESETS = { S: 20, M: 56 } as const
type BookmarkLogoSize = keyof typeof SIZE_PRESETS

// preferHd 已移除：用哪张图是服务端按展示模式决定的（AssetRolePolicy），
// 前端再传一个偏好只会和服务端策略打架。原先这个 prop 也从未被任何调用方传过。
const props = defineProps<{
  value: BookmarkShow
  size?: BookmarkLogoSize | number
  /**
   * 用哪一份解析结果。默认跟着 size 走：M（56px 磁贴）用 tile，其余用 list。
   *
   * 这个默认值就是「置顶区该拿 TILE 那一档」这条规则的落点 —— 调用方只说自己多大，
   * 不必知道后端有两种展示模式。显式传值只在极少数尺寸与语义不一致的场景需要。
   */
  variant?: BookmarkIconVariant
}>()

// 统一的灰：「不可访问」蒙版与「本地/IP」底色共用，保证两种状态视觉一致
const INACTIVE_GRAY = 'rgba(100, 116, 139, 0.62)'

const logoSize = computed(() => {
  const size = props.size ?? SIZE_PRESETS.M
  return typeof size === 'number' ? size : SIZE_PRESETS[size]
})

// 没显式指定时按尺寸推断：只有 M 这一档（置顶区磁贴）是大图位
const variant = computed<BookmarkIconVariant>(() => props.variant ?? (props.size === 'M' ? 'tile' : 'list'))

const { kind, isInactive, displaySrc, surfaceColor, padding, scale, monogramChar, monogramHue, onIconError } =
  useBookmarkIcon(toRef(props, 'value'), variant)

// 灰底上的白色图标（本地/IP 的圆点、不可访问的断网标识）共用一套尺寸，保证两种状态外观一致：
// 取图标尺寸的 50%，并设 10px 下限，避免 20px 的列表小格子下细到看不清
const glyphStyle = computed(() => {
  const glyph = Math.max(10, Math.round(logoSize.value * 0.5))
  return { width: `${glyph}px`, height: `${glyph}px` }
})

// 明文 http 曾经在这里额外叠一个「不支持 SSL」的感叹号蒙版，已移除：它与「不可访问」共用
// 同一层灰蒙版和去色滤镜，在 20px 的列表格子里两者几乎分辨不出，实际效果是把一批活得好好的
// 站点显示成失活；而判据是前端拿 urlFull 的协议现算的，后台没有任何对应字段可供核对。
// 站点用不用 SSL 也不该由书签图标来提示 —— 浏览器地址栏本来就在做这件事。

const logoSizeStyle = computed(() => ({ width: `${logoSize.value}px`, height: `${logoSize.value}px` }))

const logoStyle = computed(() => {
  // 本地/IP：铺与「不可访问」蒙版同一个灰（叠在 bg-white 上合成同色），配白色图标
  if (kind.value === 'plain') return { backgroundColor: INACTIVE_GRAY }
  // 底色只有自动取色这一个来源：管理员按展示模式人工设底色/内边距那条链路已于 2026-08-17
  // 连同 site_display_pref 表一并移除（理由见根目录 docs/ICON-DISPLAY-TODO.md）
  if (surfaceColor.value) return { backgroundColor: surfaceColor.value }
  return undefined
})

/**
 * 图片默认铺满卡片，两个方向的例外都由 icon/appearance 从图标字节现算（服务端手里没有字节，
 * 它只从 scrapper 收到宽高和哈希），且都刻意保守 —— 漏判只是维持现状，误判会让一批本来
 * 正常的图标集体变形：
 *
 * - **「透明底 + 图形顶到边」补一圈留白**：那种图标铺满时图形正贴着圆角边缘，看着像被裁了
 *   一刀，而同屏那些自带留白的图标是缩在中间的，两种摆法混在一起尤其难看。
 * - **「外缘自带颜色的实心图标」放大顶掉它自带的透明边距**：小红书那类 app icon（红色圆角
 *   方块）的 PNG 里烤进了一圈透明边距，铺进卡片就是卡片套卡片，看起来正像是我们给它加了
 *   padding。`transform` 不占布局，超出的部分由外层 `overflow-hidden` 的圆角卡片裁掉。
 *
 * `objectFit: contain` 保证非方形的图不被拉变形。矢量图与大图都已由服务端按模式缩放好。
 */
const imageStyle = computed(() => ({
  width: '100%',
  height: '100%',
  objectFit: 'contain' as const,
  padding: padding.value > 0 ? `${(padding.value * 100).toFixed(1)}%` : undefined,
  transform: scale.value > 1 ? `scale(${scale.value.toFixed(3)})` : undefined,
}))

const monogramStyle = computed(() => ({
  width: '100%',
  height: '100%',
  backgroundColor: `hsl(${monogramHue.value} 55% 55%)`,
  fontSize: `${Math.max(10, Math.round(logoSize.value * 0.42))}px`,
}))
</script>

<style scoped>
.monogram {
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  color: #fff;
  user-select: none;
}

.inactive-logo {
  /* 网站不可访问：图标去色，压暗交给上层灰色蒙版 */
  filter: grayscale(100%);
}

.inactive-mask {
  /* 灰色蒙版：铺满整个图标卡片（外层 overflow-hidden 已裁出圆角），居中放置白色断网标识 */
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 底色由 INACTIVE_GRAY 内联注入，与「本地/IP」灰底共用同一个值 */
  color: #ffffff;
  pointer-events: none;
}
</style>
