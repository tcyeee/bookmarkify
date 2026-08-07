<script lang="ts" setup>
/**
 * 设置页的一行：左边名称（可带说明与帮助图标），右边控件。
 *
 * 说明文字写在 `help` 里而不是堆在页面底部：底部那段长解释与具体某一项的对应关系全靠读者自己找，
 * 而调参的人是盯着某一行发愣时才需要解释的。
 */
import { defineAsyncComponent } from 'vue';

defineProps<{
  /** 悬浮帮助图标时显示的长说明，留空则不出图标 */
  help?: string;
  label: string;
  /** 一句话副标题，长解释放 help */
  tip?: string;
}>();

const ElTooltip = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/tooltip/index'),
    import('element-plus/es/components/tooltip/style/css'),
  ]).then(([res]) => res.ElTooltip),
);
</script>

<template>
  <div
    class="border-border flex items-center justify-between gap-8 border-b border-solid border-x-0 border-t-0 py-3.5 last:border-b-0"
  >
    <div class="min-w-0">
      <div class="text-foreground flex items-center gap-1 text-sm">
        <span>{{ label }}</span>
        <ElTooltip v-if="help" effect="dark" placement="top">
          <template #content>
            <div class="max-w-80 text-xs leading-relaxed">{{ help }}</div>
          </template>
          <svg
            class="text-muted-foreground hover:text-foreground size-3.5 cursor-help transition-colors"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            viewBox="0 0 24 24"
          >
            <circle cx="12" cy="12" r="10" />
            <path
              d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3"
              stroke-linecap="round"
            />
            <path d="M12 17h.01" stroke-linecap="round" />
          </svg>
        </ElTooltip>
      </div>
      <p v-if="tip" class="text-muted-foreground mt-1 text-xs">{{ tip }}</p>
    </div>

    <div class="flex shrink-0 items-center gap-2">
      <slot></slot>
    </div>
  </div>
</template>
