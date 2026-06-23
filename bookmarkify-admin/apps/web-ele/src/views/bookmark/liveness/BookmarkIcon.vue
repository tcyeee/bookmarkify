<script lang="ts" setup>
import type { BookmarkEntity } from "#/api/bookmark";

import { computed, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    value: BookmarkEntity;
    size?: number;
    padding?: number;
    bgColor?: string;
    // 高清直链：存在则优先渲染（纯 img 铺白底，不做平均色），对齐前台 iconHdUrl 行为
    hdUrl?: string;
  }>(),
  { size: 72 },
);

// 实时预览时由外部传入 padding 覆盖；否则取书签自身已保存的 iconPadding
const effectivePadding = computed(() => props.padding ?? props.value.iconPadding ?? 0);

// 自定义背景色：优先用外部传入（实时预览），其次书签已保存值；为空则用图标平均色
const customBgColor = computed(() => props.bgColor ?? props.value.iconBgColor ?? "");

// 无图标 / 加载失败时的兜底图标（灰色地球，内联 SVG 避免依赖静态资源）
const FALLBACK_ICON = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>`,
)}`;

// 状态：HD/base64 错误标记、动态背景色、是否放大
const hdError = ref(false);
const iconError = ref(false);
const backgroundColor = ref("#ffffff");
const shouldUpscale = ref(false);

const logoSize = computed(() => props.size);
// 取源优先级：HD 直链 → base64 → 兜底（与前台 BookmarkLogo 一致）
const hasHd = computed(() => !hdError.value && !!props.hdUrl);
const hasBase64 = computed(
  () => !hasHd.value && !iconError.value && !!props.value.iconBase64,
);
const base64Src = computed(() => buildBase64DataUrl(props.value.iconBase64));

// 外层卡片尺寸
const cardStyle = computed(() => ({
  width: `${logoSize.value}px`,
  height: `${logoSize.value}px`,
}));

// 内层 Logo：自定义背景色时直接铺该色；否则 base64 叠加主色与淡白蒙版，无图标用纯白底
const logoStyle = computed(() => {
  if (customBgColor.value) {
    return { backgroundColor: customBgColor.value };
  }
  return hasBase64.value
    ? {
        backgroundColor: backgroundColor.value,
        backgroundImage:
          "linear-gradient(rgba(255,255,255,0.88), rgba(255,255,255,0.58))",
      }
    : { backgroundColor: "#ffffff" };
});

// base64 尺寸：源图 >= 64px 放大到 60%，否则 40%；再按内边距百分比收缩（与前台一致）
const base64PixelSize = computed(() => {
  const base = logoSize.value * (shouldUpscale.value ? 0.6 : 0.4);
  // 内边距按比例收缩(相对图标尺寸),避免小格子下被绝对像素减成负值而塌成 4px
  const shrink = 1 - Math.min(Math.max(effectivePadding.value, 0), 35) / 100;
  return Math.max(4, Math.round(base * shrink));
});
const base64Style = computed(() => ({
  width: `${base64PixelSize.value}px`,
  height: `${base64PixelSize.value}px`,
}));
const fallbackStyle = computed(() => {
  const px = Math.round(logoSize.value * 0.4);
  return { width: `${px}px`, height: `${px}px` };
});

function onHdError() {
  hdError.value = true;
}

function onIconError() {
  iconError.value = true;
}

// 监听 hdUrl 变化：重置 HD 加载错误标记以便重新加载
watch(
  () => props.hdUrl,
  () => {
    hdError.value = false;
  },
);

// 监听 iconBase64 变化：重置错误状态并重新计算主色
watch(
  () => props.value.iconBase64,
  async (base64) => {
    iconError.value = false;
    if (!base64) {
      backgroundColor.value = "#ffffff";
      shouldUpscale.value = false;
      return;
    }
    try {
      const { color, upscale } = await analyzeBase64(base64);
      backgroundColor.value = color;
      shouldUpscale.value = upscale;
    } catch {
      backgroundColor.value = "#ffffff";
      shouldUpscale.value = false;
      iconError.value = true;
    }
  },
  { immediate: true },
);

// 加载 base64，得到主色与放大标记
async function analyzeBase64(
  base64: string,
): Promise<{ color: string; upscale: boolean }> {
  const img = await loadBase64Image(base64);
  const upscale = Math.max(img.width, img.height) >= 64;
  const color = computeAverageColor(img);
  return { color, upscale };
}

// 计算平均色，步长取样兼顾性能
function computeAverageColor(img: HTMLImageElement): string {
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d");

  if (!ctx || !img.width || !img.height) {
    return "#ffffff";
  }

  const targetWidth = Math.min(img.width, 64);
  const targetHeight = Math.min(img.height, 64);
  canvas.width = targetWidth;
  canvas.height = targetHeight;

  ctx.drawImage(img, 0, 0, targetWidth, targetHeight);
  const { data } = ctx.getImageData(0, 0, targetWidth, targetHeight);

  let r = 0;
  let g = 0;
  let b = 0;
  let count = 0;

  // 取平均色，步长减少开销
  const step = 4 * 4;
  for (let i = 0; i + 3 < data.length; i += step) {
    const alpha = data[i + 3] ?? 0;
    if (!alpha) continue;
    r += data[i] ?? 0;
    g += data[i + 1] ?? 0;
    b += data[i + 2] ?? 0;
    count++;
  }

  if (!count) return "#ffffff";

  return rgbToHex(
    Math.round(r / count),
    Math.round(g / count),
    Math.round(b / count),
  );
}

// 载入 base64 图片
function loadBase64Image(base64: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.addEventListener("load", () => resolve(img));
    img.addEventListener("error", reject);
    img.src = buildBase64DataUrl(base64);
  });
}

function buildBase64DataUrl(base64?: string): string {
  if (!base64) return "";
  const trimmed = base64.trim();
  if (trimmed.startsWith("data:") || trimmed.startsWith("http")) return trimmed;
  const mime = detectMimeFromBase64(trimmed);
  return `data:${mime};base64,${trimmed}`;
}

function detectMimeFromBase64(base64: string): string {
  try {
    const raw = atob(base64.slice(0, 240));
    const trimmed = raw.trimStart();
    if (trimmed.startsWith("\x89PNG")) return "image/png";
    if (trimmed.startsWith("\xFF\xD8\xFF")) return "image/jpeg";
    if (trimmed.startsWith("GIF8")) return "image/gif";
    if (
      trimmed.startsWith("<svg") ||
      trimmed.startsWith("<?xml") ||
      trimmed.toLowerCase().includes("<svg")
    )
      return "image/svg+xml";
  } catch {
    // ignore and fall back
  }
  return "image/png";
}

function rgbToHex(r: number, g: number, b: number): string {
  return `#${[r, g, b]
    .map((val) => {
      const hex = val.toString(16);
      return hex.length === 1 ? `0${hex}` : hex;
    })
    .join("")}`;
}
</script>

<template>
  <!-- 外层卡片：圆角与阴影，与前台 BookmarkLogo 一致 -->
  <div class="icon-card" :style="cardStyle">
    <!-- 内层 Logo：HD 优先纯铺白底；base64 时叠加主色与淡白蒙版；否则兜底地球 -->
    <div class="icon-logo" :style="logoStyle">
      <!-- 优先使用高清图 -->
      <img
        v-if="hasHd"
        :src="hdUrl"
        alt=""
        draggable="false"
        @error="onHdError"
      />
      <img
        v-else-if="hasBase64"
        :style="base64Style"
        :src="base64Src"
        alt=""
        draggable="false"
        @error="onIconError"
      />
      <img
        v-else
        :style="fallbackStyle"
        :src="FALLBACK_ICON"
        alt=""
        draggable="false"
      />
    </div>
  </div>
</template>

<style scoped>
.icon-card {
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 22%;
  background: #f3f4f6;
  box-shadow: 0 1px 4px rgb(0 0 0 / 12%);
  transition: transform 0.15s ease;
}

.icon-logo {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
}

.icon-logo img {
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}
</style>
