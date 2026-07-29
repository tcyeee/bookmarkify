<template>
  <!-- 外层容器：卡片圆角。shrink-0 必需——多处调用把本组件放进 flex 行里与超长标题同排，
       没有它时卡片会被 flex 横向压扁成长方形（overflow-hidden 再把图标裁掉） -->
  <div class="relative shrink-0 rounded-[22%] bg-gray-100 center overflow-hidden">
    <!-- 内层 Logo：默认白底，必要时覆盖主色与淡白蒙版；不可访问时整体变灰 -->
    <div
      class="bg-white flex justify-center items-center"
      :class="{ 'inactive-logo': isInactive || isInsecure }"
      :style="[logoSizeStyle, logoStyle]">
      <!-- 本地/IP 类型书签：后端不抓取信息，用与「不可访问」同色的灰底 + 白色圆点图标 -->
      <Icon v-if="isPlainCircle" icon="mdi:dots-circle" class="shrink-0 text-white" :style="glyphStyle" />
      <!-- 服务端已按展示模式选好唯一一张图，前端不再在多个图位之间取舍 -->
      <img
        v-else-if="imageUrl && !iconError"
        :key="imageUrl"
        :style="imageStyle"
        :src="imageUrl"
        alt=""
        draggable="false"
        @error="onIconError"
      />
      <!-- 首字母色块：该站没有够格的图（monogram），硬把 32px 的 favicon 拉到 72px 只会更难看 -->
      <div v-else class="monogram" :style="monogramStyle">{{ monogramChar }}</div>
    </div>

    <!-- 不可访问 / 不支持 SSL：整体覆盖同一层灰色蒙版，居中叠加白色标识（断网 / 感叹号）以区分两种状态 -->
    <div
      v-if="isInactive || isInsecure"
      class="inactive-mask"
      :style="{ backgroundColor: INACTIVE_GRAY }"
      :aria-label="isInactive ? '无法访问' : '不支持 SSL'">
      <svg
        v-if="isInactive"
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
      <Icon v-else icon="mdi:exclamation-thick" class="shrink-0" :style="glyphStyle" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { BookmarkLinkType, type BookmarkShow } from '@typing'

// preferHd 已移除：用哪张图是服务端按展示模式决定的（AssetRolePolicy），
// 前端再传一个偏好只会和服务端策略打架。原先这个 prop 也从未被任何调用方传过。
const props = defineProps<{ value: BookmarkShow; size?: number }>()

// 统一的灰：「不可访问」蒙版与「本地/IP」底色共用，保证两种状态视觉一致
const INACTIVE_GRAY = 'rgba(100, 116, 139, 0.62)'

const iconError = ref(false)
const logoSize = computed(() => props.size ?? 80)

// 本地(localhost/127.0.0.1)或纯 IP 地址类型的书签：后端不抓取网站信息，前端仅展示统一的 mdi 圆圈图标
const isPlainCircle = computed(
  () => props.value.linkType === BookmarkLinkType.LOCAL || props.value.linkType === BookmarkLinkType.IP,
)

// 灰底上的白色图标（本地/IP 的圆点、不可访问的断网标识）共用一套尺寸，保证两种状态外观一致：
// 取图标尺寸的 50%，并设 10px 下限，避免 20px 的列表小格子下细到看不清
const glyphStyle = computed(() => {
  const glyph = Math.max(10, Math.round(logoSize.value * 0.5))
  return { width: `${glyph}px`, height: `${glyph}px` }
})

// 自定义背景色 / 内边距：管理台按展示模式设置，服务端已随 logo 一并下发
const customBgColor = computed(() => props.value.logo?.iconBgColor || '')
const effectivePadding = computed(() => props.value.logo?.iconPadding ?? 0)

// 不支持 SSL(明文 http)：后端不下发该标记，前端按 urlFull 的协议判定。
// 本地/IP 天然多为 http，已有各自的展示形态；「不可访问」优先级更高，两者都不再叠加此状态
const isInsecure = computed(() => {
  if (isPlainCircle.value || props.value?.isActivity === false) return false
  return /^http:\/\//i.test(props.value?.urlFull || '')
})

// 网站不可访问：图标变灰并叠加断网标识
const isInactive = computed(() => props.value?.isActivity === false)

// monogram 为 true 表示"该站没有够格的图"，此时不渲染图片
const imageUrl = computed(() => (props.value.logo?.monogram ? '' : props.value.logo?.url || ''))

const logoSizeStyle = computed(() => ({ width: `${logoSize.value}px`, height: `${logoSize.value}px` }))

const logoStyle = computed(() => {
  // 本地/IP：铺与「不可访问」蒙版同一个灰（叠在 bg-white 上合成同色），配白色图标
  if (isPlainCircle.value) return { backgroundColor: INACTIVE_GRAY }
  if (customBgColor.value) return { backgroundColor: customBgColor.value }
  return undefined
})

// 图片按内边距收缩；矢量图与大图都由服务端按模式缩放好，这里只做留白
const imageStyle = computed(() => {
  const shrink = 1 - Math.min(Math.max(effectivePadding.value, 0), 35) / 100
  const px = Math.max(4, Math.round(logoSize.value * shrink))
  return { width: `${px}px`, height: `${px}px`, objectFit: 'contain' as const }
})

// ── 首字母色块 ──
// 取标题/域名首字符；中文直接用该字，英文用大写字母
const monogramChar = computed(() => {
  const src = props.value.title || props.value.urlBase || props.value.urlFull || '?'
  const ch = src.replace(/^https?:\/\//i, '').trim().charAt(0)
  return (ch || '?').toUpperCase()
})

// 由标题散列出稳定的色相：同一个书签每次渲染颜色一致，不同书签易于区分
const monogramStyle = computed(() => {
  const src = props.value.title || props.value.urlBase || '?'
  let hash = 0
  for (let i = 0; i < src.length; i++) hash = (hash * 31 + src.charCodeAt(i)) >>> 0
  const hue = hash % 360
  return {
    width: '100%',
    height: '100%',
    backgroundColor: customBgColor.value || `hsl(${hue} 55% 55%)`,
    fontSize: `${Math.max(10, Math.round(logoSize.value * 0.42))}px`,
  }
})

function onIconError() {
  iconError.value = true
}

// 换书签或换图时重置错误状态，让新图有机会加载
watch(
  () => [props.value.bookmarkId, props.value.logo?.url],
  () => {
    iconError.value = false
  },
)
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

