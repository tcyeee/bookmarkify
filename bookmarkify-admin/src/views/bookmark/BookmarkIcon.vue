<script lang="ts" setup>
import { computed, ref, watch } from "vue";

/**
 * 后台图标预览。
 *
 * 已从"base64 小图 + 高清直链"两个图位改成**单一地址** —— 用哪张图由后端按展示模式
 * 决定（AssetRolePolicy），后台只负责显示与实时预览人工调的内边距/背景色。
 *
 * 同时去掉了原先对 base64 做的平均色提取：新模型的图来自私有读 OSS 的签名地址，
 * 跨域读取会污染 canvas 取不到像素，且背景色已可由人工在此页直接指定。
 */
const props = withDefaults(
  defineProps<{
    /** 要显示的图片地址；为空则显示兜底地球 */
    src?: string;
    size?: number;
    /** 实时预览：外部传入则覆盖已保存值 */
    padding?: number;
    bgColor?: string;
  }>(),
  { size: 72 },
);

// 无图标 / 加载失败时的兜底图标（灰色地球，内联 SVG 避免依赖静态资源）
const FALLBACK_ICON = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>`,
)}`;

const iconError = ref(false);
const logoSize = computed(() => props.size);
const effectivePadding = computed(() => props.padding ?? 0);
const customBgColor = computed(() => props.bgColor ?? "");
const hasIcon = computed(() => !iconError.value && !!props.src);

const cardStyle = computed(() => ({
  width: `${logoSize.value}px`,
  height: `${logoSize.value}px`,
}));

const logoStyle = computed(() =>
  customBgColor.value ? { backgroundColor: customBgColor.value } : { backgroundColor: "#ffffff" },
);

// 按内边距百分比收缩，避免小格子下被绝对像素减成负值而塌陷
const iconStyle = computed(() => {
  const shrink = 1 - Math.min(Math.max(effectivePadding.value, 0), 35) / 100;
  const px = Math.max(4, Math.round(logoSize.value * shrink));
  return { width: `${px}px`, height: `${px}px` };
});

const fallbackStyle = computed(() => {
  const px = Math.round(logoSize.value * 0.4);
  return { width: `${px}px`, height: `${px}px` };
});

function onIconError() {
  iconError.value = true;
}

// 换图时重置错误标记，让新地址有机会加载
watch(
  () => props.src,
  () => {
    iconError.value = false;
  },
);
</script>

<template>
  <!-- 外层卡片：圆角与阴影，与前台 BookmarkLogo 一致 -->
  <div class="icon-card" :style="cardStyle">
    <div class="icon-logo" :style="logoStyle">
      <img
        v-if="hasIcon"
        :style="iconStyle"
        :src="props.src"
        alt=""
        draggable="false"
        @error="onIconError"
      />
      <img v-else :style="fallbackStyle" :src="FALLBACK_ICON" alt="" draggable="false" />
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
