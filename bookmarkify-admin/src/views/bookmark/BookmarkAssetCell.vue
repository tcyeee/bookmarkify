<script lang="ts" setup>
import { defineAsyncComponent, ref, watch } from "vue";

const props = defineProps<{
  /** 已可直接渲染的图片地址（base64 data URL 或签名后的 OSS 直链），为空表示该资源没抓到 */
  src?: string;
  /** 宽屏模式：OG 图是 1.91:1 的分享图，用方形格子会压得看不清 */
  wide?: boolean;
  /** 可点击放大。默认关闭：表格里整行点击是「打开详情」，图片再抢一层点击会打断那个语义 */
  preview?: boolean;
}>();

const emit = defineEmits<{
  /** 仅在 [preview] 开启且确有图片时触发，由调用方决定用什么查看器打开 */
  (e: "preview", src: string): void;
}>();

function handleClick() {
  if (props.preview && props.src && !loadError.value) emit("preview", props.src);
}

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag),
);

// 地址存在但加载失败（OSS 签名过期、源图 404）也按「无」处理，避免留一个破图占位
const loadError = ref(false);
watch(
  () => props.src,
  () => {
    loadError.value = false;
  },
);
</script>

<template>
  <div class="flex items-center justify-center">
    <img
      v-if="src && !loadError"
      :src="src"
      :class="[wide ? 'asset-wide' : 'asset-square', { 'asset-zoomable': preview }]"
      alt=""
      draggable="false"
      @error="loadError = true"
      @click.stop="handleClick"
    />
    <ElTag v-else type="info" size="small" disable-transitions>无</ElTag>
  </div>
</template>

<style scoped>
.asset-square {
  width: 32px;
  height: 32px;
  object-fit: contain;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}

.asset-zoomable {
  cursor: zoom-in;
  transition: border-color 0.15s ease;
}

.asset-zoomable:hover {
  border-color: var(--el-color-primary);
}

/* 1.91:1 的 OG 比例，高度与方形图标齐平，不撑高表格行 */
.asset-wide {
  width: 61px;
  height: 32px;
  object-fit: cover;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
}
</style>
