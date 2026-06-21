<script lang="ts" setup>
import type { BookmarkEntity } from "#/api/bookmark";

import { computed, defineAsyncComponent, onMounted, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage } from "element-plus";

import { getBookmarkListApi, updateBookmarkIconApi } from "#/api/bookmark";

import BookmarkIcon from "./BookmarkIcon.vue";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard),
);

const ElInput = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input/index"),
    import("element-plus/es/components/input/style/css"),
  ]).then(([res]) => res.ElInput),
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton),
);

const ElEmpty = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/empty/index"),
    import("element-plus/es/components/empty/style/css"),
  ]).then(([res]) => res.ElEmpty),
);

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog),
);

const ElLink = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/link/index"),
    import("element-plus/es/components/link/style/css"),
  ]).then(([res]) => res.ElLink),
);

const ElInputNumber = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input-number/index"),
    import("element-plus/es/components/input-number/style/css"),
  ]).then(([res]) => res.ElInputNumber),
);

const ElSegmented = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/segmented/index"),
    import("element-plus/es/components/segmented/style/css"),
  ]).then(([res]) => res.ElSegmented),
);

const ElColorPicker = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/color-picker/index"),
    import("element-plus/es/components/color-picker/style/css"),
  ]).then(([res]) => res.ElColorPicker),
);

const ElSlider = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/slider/index"),
    import("element-plus/es/components/slider/style/css"),
  ]).then(([res]) => res.ElSlider),
);

const PAGE_SIZE = 200;
const MAX_PAGES = 50; // 安全上限，避免异常分页导致死循环

const loading = ref(false);
const keyword = ref("");
const bookmarks = ref<BookmarkEntity[]>([]);

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  if (!kw) return bookmarks.value;
  return bookmarks.value.filter((b) =>
    [b.appName, b.title, b.urlHost, b.description]
      .filter(Boolean)
      .some((field) => field!.toLowerCase().includes(kw)),
  );
});

/** 拼出书签完整 URL，供点击图标时跳转 */
function fullUrl(row: BookmarkEntity): string {
  const scheme = row.urlScheme || "https";
  const path = row.urlPath || "";
  return `${scheme}://${row.urlHost}${path}`;
}

function displayName(row: BookmarkEntity): string {
  return row.appName || row.title || row.urlHost;
}

// ── 详情弹窗 ──────────────────────────────────────────────────────────────────
const detailVisible = ref(false);
const detailItem = ref<BookmarkEntity | null>(null);

// 图标设置编辑：图片内边距、背景色
const PADDING_MIN = 0;
const PADDING_MAX = 35;
const PADDING_DEFAULT = 25;
const editPadding = ref(PADDING_DEFAULT);
const editBgColor = ref<null | string>(null);
const savingIcon = ref(false);

// 预览图标大小（仅影响预览，不入库）：小 / 中 / 大
const PREVIEW_SIZES = [
  { label: "小", value: 80 },
  { label: "中", value: 120 },
  { label: "大", value: 160 },
];
const previewSize = ref(120);

const normColor = (c?: null | string) => c || "";

const iconDirty = computed(() => {
  const item = detailItem.value;
  if (!item) return false;
  return (
    editPadding.value !== item.iconPadding ||
    normColor(editBgColor.value) !== normColor(item.iconBgColor)
  );
});

function openDetail(row: BookmarkEntity) {
  detailItem.value = row;
  // 图标内边距范围 0~35，缺省值 25
  editPadding.value = Math.min(
    PADDING_MAX,
    Math.max(PADDING_MIN, row.iconPadding ?? PADDING_DEFAULT),
  );
  editBgColor.value = row.iconBgColor ?? null;
  previewSize.value = 120;
  detailVisible.value = true;
}

/** 从屏幕吸取颜色（需浏览器支持 EyeDropper API，Chrome/Edge 可用） */
async function pickScreenColor() {
  const EyeDropperCtor = (globalThis as any).EyeDropper;
  if (!EyeDropperCtor) {
    ElMessage.warning("当前浏览器不支持屏幕取色（请使用 Chrome / Edge）");
    return;
  }
  try {
    const { sRGBHex } = await new EyeDropperCtor().open();
    editBgColor.value = sRGBHex;
  } catch {
    // 用户取消取色，忽略
  }
}

/** 保存图标设置（图片内边距 iconPadding、图标背景色 iconBgColor） */
async function saveIcon() {
  const item = detailItem.value;
  if (!item) return;
  savingIcon.value = true;
  try {
    await updateBookmarkIconApi(item.id, {
      iconPadding: editPadding.value,
      iconBgColor: editBgColor.value || null,
    });
    item.iconPadding = editPadding.value;
    item.iconBgColor = editBgColor.value || undefined;
    ElMessage.success("图标设置已保存");
  } finally {
    savingIcon.value = false;
  }
}

/** 拉取全部「成功」状态的书签（自动翻页累积） */
async function fetchAll() {
  loading.value = true;
  try {
    const acc: BookmarkEntity[] = [];
    let currentPage = 1;
    let totalPages = 1;
    do {
      const res = await getBookmarkListApi({
        status: "SUCCESS",
        currentPage,
        pageSize: PAGE_SIZE,
      });
      acc.push(...res.records);
      totalPages = res.pages;
      currentPage += 1;
    } while (currentPage <= totalPages && currentPage <= MAX_PAGES);
    bookmarks.value = acc;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  fetchAll();
});
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-3">
          <span>书签平铺展示（成功 {{ bookmarks.length }} 个）</span>
          <div class="flex items-center gap-2">
            <ElInput
              v-model="keyword"
              placeholder="过滤 名称 / 标题 / 域名"
              clearable
              style="width: 220px"
            />
            <ElButton :loading="loading" @click="fetchAll">刷新</ElButton>
          </div>
        </div>
      </template>

      <div v-loading="loading" class="min-h-40">
        <div v-if="filtered.length > 0" class="grid-wall">
          <div
            v-for="item in filtered"
            :key="item.id"
            class="cell"
            draggable="false"
            :title="`${displayName(item)}\n${fullUrl(item)}`"
            @click="openDetail(item)"
            @dragstart.prevent
          >
            <BookmarkIcon :value="item" :size="72" />
            <div class="title">{{ displayName(item) }}</div>
          </div>
        </div>
        <ElEmpty v-else-if="!loading" description="暂无成功状态的书签" />
      </div>
    </ElCard>

    <ElDialog
      v-model="detailVisible"
      :title="detailItem ? detailItem.title || displayName(detailItem) : ''"
      width="600px"
      append-to-body
      class="icon-dialog"
    >
      <div v-if="detailItem" class="detail-body">
        <!-- 左右结构：左侧预览 / 右侧编辑 -->
        <div class="detail-main">
          <!-- 左侧：预览（随内边距 / 背景色实时更新；大小仅影响预览） -->
          <div class="preview-pane">
            <div class="preview-area">
              <BookmarkIcon
                :value="detailItem"
                :size="previewSize"
                :padding="editPadding"
                :bg-color="editBgColor ?? undefined"
              />
            </div>
            <ElSegmented
              v-model="previewSize"
              :options="PREVIEW_SIZES"
              size="small"
            />
          </div>

          <!-- 右侧：编辑区域 -->
          <div class="edit-pane">
            <!-- 背景颜色 -->
            <div class="edit-row">
              <span class="edit-label">背景颜色</span>
              <div class="edit-control">
                <ElColorPicker v-model="editBgColor" size="small" show-alpha />
                <ElButton size="small" @click="pickScreenColor">屏幕取色</ElButton>
              </div>
            </div>

            <!-- 图片内边距：滑块 + 输入框 -->
            <div class="edit-row">
              <span class="edit-label">图片内边距</span>
              <div class="edit-control padding-control">
                <ElSlider
                  v-model="editPadding"
                  :min="PADDING_MIN"
                  :max="PADDING_MAX"
                  :step="1"
                  class="padding-slider"
                />
                <ElInputNumber
                  v-model="editPadding"
                  :min="PADDING_MIN"
                  :max="PADDING_MAX"
                  :step="1"
                  size="small"
                  controls-position="right"
                  class="padding-input"
                />
              </div>
            </div>
          </div>
        </div>

        <!-- 最下方：网站域名小字 -->
        <div class="domain-text">
          <ElLink
            type="info"
            :href="fullUrl(detailItem)"
            target="_blank"
            :underline="false"
          >
            {{ detailItem.urlHost }}
          </ElLink>
        </div>
      </div>

      <template #footer>
        <ElButton
          type="primary"
          :loading="savingIcon"
          :disabled="!iconDirty"
          @click="saveIcon"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>
  </Page>
</template>

<style scoped>
/* 整体容器背景：作用在 el-card__body 上，用渐变写法 */
:deep(.el-card__body) {
  background-image: linear-gradient(135deg, #f8fafc 0%, #eef1f6 100%);
}

.grid-wall {
  display: grid;
  grid-template-columns: repeat(auto-fill, 96px);
  justify-content: center;
  gap: 16px 8px;
}

.cell {
  display: flex;
  width: 96px;
  cursor: pointer;
  flex-direction: column;
  align-items: center;
  user-select: none;
  -webkit-user-drag: none;
}

.detail-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 左右结构 */
.detail-main {
  display: flex;
  gap: 16px;
  align-items: stretch;
}

/* 左侧：预览面板 */
.preview-pane {
  display: flex;
  flex: 0 0 220px;
  flex-direction: column;
  gap: 12px;
  align-items: center;
}

.preview-area {
  display: flex;
  width: 100%;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  background: var(--el-fill-color-lighter);
  border-radius: 10px;
}

/* 右侧：编辑面板 */
.edit-pane {
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  gap: 18px;
  min-width: 0;
}

.edit-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.edit-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.edit-control {
  display: flex;
  align-items: center;
  gap: 12px;
}

.padding-control {
  gap: 16px;
}

.padding-slider {
  flex: 1;
  min-width: 0;
}

.padding-input {
  width: 110px;
  flex: none;
}

/* 最下方：网站域名小字 */
.domain-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}

/* 图标卡片样式由 BookmarkIcon 组件自身负责，此处仅补 hover 放大 */
.cell:hover :deep(.icon-card) {
  transform: scale(1.06);
}

.title {
  margin-top: 6px;
  width: 100%;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.3;
  color: var(--el-text-color-regular);
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>

<!-- 弹窗被 append-to-body 传送到 body，scoped 无法命中其内部，故用非 scoped 样式 -->
<style>
.icon-dialog .el-dialog__header,
.icon-dialog .el-dialog__body,
.icon-dialog .el-dialog__footer {
  padding: 15px;
}

.icon-dialog .el-dialog__body {
  padding-top: 5px;
}
</style>
