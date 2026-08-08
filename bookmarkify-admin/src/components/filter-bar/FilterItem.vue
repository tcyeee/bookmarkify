<script lang="ts" setup>
/**
 * 筛选栏里的一项：左边一个标签，右边一个定宽控件。
 *
 * 用它而不是 `ElFormItem`：筛选栏不需要表单的任何能力（不校验、不提交、没有 label-width 联动），
 * 而 `ElFormItem` 的标签是加粗的深色字、还带一个只能靠 `!mb-0` 抵消的下边距 —— 八个页面于是
 * 各自复制了一份 `!mb-0` 加一段 `:deep(.el-form-item__label)` 覆盖。这里直接把版式定死。
 *
 * 控件宽度由这一层给，插槽里的控件不再各写 `style="width: …"`：同一种筛选项在不同页面宽度不一
 * 是最显眼的不统一。开关这类本身就该保持原生宽度的控件传 `width="auto"`。
 */
withDefaults(defineProps<{ label?: string; width?: string }>(), {
  label: "",
  width: "160px",
});
</script>

<template>
  <div class="filter-item">
    <span v-if="label" class="filter-item__label">{{ label }}</span>
    <div
      class="filter-item__control"
      :class="{ 'filter-item__control--auto': width === 'auto' }"
      :style="{ width }"
    >
      <slot />
    </div>
  </div>
</template>

<style scoped>
.filter-item {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.filter-item__label {
  font-size: 14px;
  line-height: 32px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
}

.filter-item__control {
  display: flex;
  align-items: center;
}
</style>

<style>
/*
 * 不加 scoped：插槽里的控件带的是调用方页面的作用域标记，scoped 选择器选不到它们。
 * 作用域靠类名前缀收窄，只影响筛选项自己的直接子节点。
 */
.filter-item__control > * {
  width: 100%;
}

.filter-item__control--auto > * {
  width: auto;
}
</style>
