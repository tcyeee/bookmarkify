<template>
  <!-- 外层容器：卡片圆角与开发态提示 -->
  <div
    :class="[
      'relative w-app h-app rounded-[22%] bg-gray-100 center shadow overflow-hidden',
      isDev ? 'dev-outline dev-shrink' : '',
    ]"
    :style="isDev ? { '--dev-outline-color': devOutlineColor } : undefined">
    <!-- 内层 Logo：默认白底，必要时覆盖主色与淡白蒙版；不可访问时整体变灰 -->
    <div
      class="bg-white flex justify-center items-center"
      :class="{ 'inactive-logo': isInactive }"
      :style="[logoSizeStyle, logoStyle]">
      <!-- 优先使用高清图（仅在调用方显式要求时，如置顶区域） -->
      <img
        v-if="props.preferHd && props.value.logo?.iconHdUrl && !hdError"
        :key="`hd-${props.value.logo?.iconHdUrl}`"
        :src="props.value.logo?.iconHdUrl"
        alt=""
        @error="onHdError"
      />
      <!-- base64 图：可按尺寸放大 -->
      <img
        v-else-if="!iconError"
        :key="`base64-${props.value.logo?.iconBase64?.slice(0, 20) || ''}`"
        :style="base64Style"
        :src="base64Src"
        alt=""
        @error="onIconError"
      />
      <!-- 最终兜底：灰色地球（内联 SVG，与管理台一致） -->
      <img v-else :style="fallbackStyle" :src="FALLBACK_ICON" alt="" />
    </div>
    <!-- 不可访问：叠加断网标识（内联 SVG，居中偏下） -->
    <div v-if="isInactive" class="offline-badge" :style="offlineBadgeStyle" aria-label="无法访问">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="100%" height="100%">
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
import { computed, ref, watch } from 'vue'
import type { BookmarkShow } from '@typing'

const props = defineProps<{ value: BookmarkShow; size?: number; preferHd?: boolean }>()

// 无图标 / 加载失败时的兜底图标（灰色地球，内联 SVG 避免依赖静态资源）
const FALLBACK_ICON = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>`,
)}`

// 状态：错误标记、动态背景色、是否放大
const hdError = ref(false)
const iconError = ref(false)
const backgroundColor = ref('#ffffff')
const shouldUpscale = ref(false)
const logoSize = computed(() => props.size ?? 80)

// 自定义背景色（管理台设置）：存在则直接铺该色
const customBgColor = computed(() => props.value.logo?.iconBgColor || '')
// 图片内边距（管理台设置）：收缩 base64 图标
const effectivePadding = computed(() => props.value.logo?.iconPadding ?? 0)

// 开发环境标记
const isDev = computed(() => isLocalhostOrIP(props.value.urlFull))
// 网站不可访问：图标变灰并叠加断网标识
const isInactive = computed(() => props.value?.isActivity === false)
// 断网标识尺寸随图标尺寸缩放（约 38%）
const offlineBadgeStyle = computed(() => {
  const badge = Math.round(logoSize.value * 0.38)
  return {
    width: `${badge}px`,
    height: `${badge}px`,
  }
})
// 判定是否需走 base64 分支
const shouldUseBase64 = computed(
  () => (!props.preferHd || !props.value.logo?.iconHdUrl || hdError.value) && !iconError.value && !!props.value.logo?.iconBase64,
)
const devOutlineColor = computed(() => backgroundColor.value || '#ffffff')
// base64 时叠加主色与淡白蒙版
const logoSizeStyle = computed(() => ({
  width: `${logoSize.value}px`,
  height: `${logoSize.value}px`,
}))
const logoStyle = computed(() => {
  if (customBgColor.value) {
    return { backgroundColor: customBgColor.value }
  }
  return shouldUseBase64.value
    ? {
        backgroundColor: backgroundColor.value,
        backgroundImage: 'linear-gradient(rgba(255,255,255,0.88), rgba(255,255,255,0.58))',
      }
    : undefined
})
// base64 尺寸：随外部 size 同步，保持原有比例
const base64PixelSize = computed(() => {
  const base = logoSize.value * (shouldUpscale.value ? 0.6 : 0.4)
  // 内边距按比例收缩(相对图标尺寸),避免小格子下被绝对像素减成负值而塌成 4px
  const shrink = 1 - Math.min(Math.max(effectivePadding.value, 0), 35) / 100
  return Math.max(4, Math.round(base * shrink))
})
const base64Style = computed(() => ({
  width: `${base64PixelSize.value}px`,
  height: `${base64PixelSize.value}px`,
}))
const fallbackStyle = computed(() => ({
  width: `${Math.round(logoSize.value * 0.4)}px`,
  height: `${Math.round(logoSize.value * 0.4)}px`,
}))
const base64Src = computed(() => buildBase64DataUrl(props.value.logo?.iconBase64 || ''))

function onHdError() {
  hdError.value = true
}

function onIconError() {
  iconError.value = true
}

// 监听 iconBase64 变化
watch(
  () => props.value.logo?.iconBase64,
  async (base64) => {
    if (!import.meta.client || !base64) {
      backgroundColor.value = '#ffffff'
      shouldUpscale.value = false
      return
    }

    try {
      const { color, upscale } = await analyzeBase64(base64)
      backgroundColor.value = color
      shouldUpscale.value = upscale
    } catch {
      backgroundColor.value = '#ffffff'
      shouldUpscale.value = false
      iconError.value = true
    }
  },
  { immediate: true },
)

// 监听 iconHdUrl 变化，重置错误状态以便重新加载
watch(
  () => props.value.logo?.iconHdUrl,
  () => {
    hdError.value = false
  },
)

// 监听整个 value 对象变化，重置所有错误状态
watch(
  () => props.value,
  () => {
    hdError.value = false
    iconError.value = false
  },
  { deep: true },
)

// 加载 base64，得到主色与放大标记
async function analyzeBase64(base64: string): Promise<{ color: string; upscale: boolean }> {
  const img = await loadBase64Image(base64)
  const upscale = Math.max(img.width, img.height) >= 64
  const color = computeAverageColor(img)
  return { color, upscale }
}

// 计算平均色，步长取样兼顾性能
function computeAverageColor(img: HTMLImageElement): string {
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')

  if (!ctx || !img.width || !img.height) {
    return '#ffffff'
  }

  const targetWidth = Math.min(img.width, 64)
  const targetHeight = Math.min(img.height, 64)
  canvas.width = targetWidth
  canvas.height = targetHeight

  ctx.drawImage(img, 0, 0, targetWidth, targetHeight)
  const { data } = ctx.getImageData(0, 0, targetWidth, targetHeight)

  let r = 0
  let g = 0
  let b = 0
  let count = 0

  // 取平均色，步长减少开销
  const step = 4 * 4
  for (let i = 0; i + 3 < data.length; i += step) {
    const alpha = data[i + 3] ?? 0
    if (!alpha) continue
    r += data[i] ?? 0
    g += data[i + 1] ?? 0
    b += data[i + 2] ?? 0
    count++
  }

  if (!count) return '#ffffff'

  return rgbToHex(Math.round(r / count), Math.round(g / count), Math.round(b / count))
}

// 载入 base64 图片
function loadBase64Image(base64: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = buildBase64DataUrl(base64)
  })
}

function buildBase64DataUrl(base64: string): string {
  if (!base64) return ''
  const trimmed = base64.trim()
  if (trimmed.startsWith('data:')) return trimmed
  const mime = detectMimeFromBase64(trimmed)
  return `data:${mime};base64,${trimmed}`
}

function detectMimeFromBase64(base64: string): string {
  if (!import.meta.client) return 'image/png'

  try {
    const raw = atob(base64.slice(0, 240))
    const trimmed = raw.trimStart()
    if (trimmed.startsWith('\x89PNG')) return 'image/png'
    if (trimmed.startsWith('\xff\xd8\xff')) return 'image/jpeg'
    if (trimmed.startsWith('GIF8')) return 'image/gif'
    if (trimmed.startsWith('<svg') || trimmed.startsWith('<?xml') || trimmed.toLowerCase().includes('<svg')) return 'image/svg+xml'
  } catch {
    // ignore and fall back
  }

  return 'image/png'
}

function rgbToHex(r: number, g: number, b: number): string {
  return `#${[r, g, b]
    .map((val) => {
      const hex = val.toString(16)
      return hex.length === 1 ? `0${hex}` : hex
    })
    .join('')}`
}

function isLocalhostOrIP(url: string): boolean {
  const localhostRegex = /^(localhost|127\.0\.0\.1|::1)$/i
  const ipRegex = /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/

  try {
    const hostname = new URL(url).hostname
    return localhostRegex.test(hostname) || ipRegex.test(hostname)
  } catch {
    return false
  }
}
</script>

<style scoped>
.dev-outline {
  /* 外描边不占空间，避免缩小内部图标 */
  outline: 4px dashed var(--dev-outline-color, #ffffff);
  outline-offset: 0;
}

.dev-shrink {
  /* 稍微缩放，让描边后的整体与正常图标一致 */
  transform: scale(0.9);
  transform-origin: center;
}

.inactive-logo {
  /* 网站不可访问：图标去色并降低不透明度 */
  filter: grayscale(100%);
  opacity: 0.5;
}

.offline-badge {
  /* 断网标识：绝对定位，居中偏下 */
  position: absolute;
  left: 50%;
  top: 58%;
  transform: translate(-50%, -50%);
  color: rgba(255, 255, 255, 0.95);
  /* 深色描边保证在浅色图标上也可见 */
  filter: drop-shadow(0 0 1px rgba(0, 0, 0, 0.55)) drop-shadow(0 1px 2px rgba(0, 0, 0, 0.45));
  pointer-events: none;
}
</style>

