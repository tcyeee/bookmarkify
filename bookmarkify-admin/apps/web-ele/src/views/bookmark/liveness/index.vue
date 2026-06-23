<script lang="ts" setup>
import type { BookmarkEntity, BookmarkRefetchResult } from "#/api/bookmark";

import { computed, defineAsyncComponent, onMounted, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage } from "element-plus";

import {
  applyRefetchBookmarkApi,
  generateAppNameApi,
  getBookmarkListApi,
  refetchBookmarkApi,
  updateBookmarkIconApi,
} from "#/api/bookmark";

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
const PADDING_MAX = 15;
const PADDING_DEFAULT = 10;
const editPadding = ref(PADDING_DEFAULT);
const editBgColor = ref<null | string>(null);
const savingIcon = ref(false);
// 是否使用高清图（落库）、书签简称（落库）、DeepSeek 生成中
const editUseHdLogo = ref(false);
const editAppName = ref("");
const generatingAppName = ref(false);

// 预览图标大小（仅影响预览，不入库）：小 / 中 / 大
const PREVIEW_SIZES = [
  { label: "小", value: 80 },
  { label: "中", value: 120 },
  { label: "大", value: 160 },
];

const normColor = (c?: null | string) => c || "";

const iconDirty = computed(() => {
  const item = detailItem.value;
  if (!item) return false;
  return (
    editPadding.value !== item.iconPadding ||
    normColor(editBgColor.value) !== normColor(item.iconBgColor) ||
    editUseHdLogo.value !== (item.useHdLogo ?? false) ||
    (editAppName.value.trim() || "") !== (item.appName || "")
  );
});

// ── 重新获取（重新解析标题/图标，预览对比后选择旧/新）──────────────────────────
const refetching = ref(false);
const refetchResult = ref<BookmarkRefetchResult | null>(null);
const chooseTitle = ref<"new" | "old">("new");
// 小图标与大图标(高清 LOGO)分开选择
const chooseIcon = ref<"new" | "old">("new");
const chooseLogo = ref<"new" | "old">("new");

// 「更新」区域大图标（高清 LOGO）加载失败标记：旧 / 新各一份
const oldLogoError = ref(false);
const newLogoError = ref(false);

// 有「重新获取」结果待应用，或图标设置有改动，均可保存
const canSave = computed(() => iconDirty.value || refetchResult.value !== null);

function openDetail(row: BookmarkEntity) {
  detailItem.value = row;
  // 图标内边距范围 0~15，缺省值 10
  editPadding.value = Math.min(
    PADDING_MAX,
    Math.max(PADDING_MIN, row.iconPadding ?? PADDING_DEFAULT),
  );
  editBgColor.value = row.iconBgColor ?? null;
  editUseHdLogo.value = row.useHdLogo ?? false;
  editAppName.value = row.appName ?? "";
  oldLogoError.value = false;
  newLogoError.value = false;
  // 每次打开重置「重新获取」状态
  refetchResult.value = null;
  chooseTitle.value = "new";
  chooseIcon.value = "new";
  chooseLogo.value = "new";
  detailVisible.value = true;
}

/** 重新获取：调用后端重新解析，拿到新标题/新图标用于对比 */
async function refetch() {
  const item = detailItem.value;
  if (!item) return;
  refetching.value = true;
  try {
    refetchResult.value = await refetchBookmarkApi(item.id);
    chooseTitle.value = "new";
    chooseIcon.value = "new";
    chooseLogo.value = "new";
    oldLogoError.value = false;
    newLogoError.value = false;
  } finally {
    refetching.value = false;
  }
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

/** DeepSeek 生成书签简称，填入编辑框（仍可手改） */
async function generateAppName() {
  const item = detailItem.value;
  if (!item) return;
  generatingAppName.value = true;
  try {
    const res = await generateAppNameApi(item.id);
    if (res.appName) {
      editAppName.value = res.appName;
      ElMessage.success("已生成");
    } else {
      ElMessage.warning("未能生成简称（标题为空或模型无结果）");
    }
  } finally {
    generatingAppName.value = false;
  }
}

/**
 * 保存：先应用「重新获取」的选择（新标题/新图标），再保存图标设置（内边距/背景色）
 */
async function saveIcon() {
  const item = detailItem.value;
  if (!item) return;
  savingIcon.value = true;
  try {
    // 1. 应用重新获取结果（按用户对旧/新的选择）
    if (refetchResult.value) {
      const updated = await applyRefetchBookmarkApi(item.id, {
        useNewTitle: chooseTitle.value === "new",
        useNewIcon: chooseIcon.value === "new",
        useNewLogo: chooseLogo.value === "new",
      });
      item.title = updated.title;
      item.iconBase64 = updated.iconBase64;
      item.logoUrl = updated.logoUrl;
      item.maximalLogoSize = updated.maximalLogoSize;
      refetchResult.value = null;
    }
    // 2. 保存图标设置（内边距 / 背景色 / 高清图开关 / 书签简称）
    if (iconDirty.value) {
      const nextAppName = editAppName.value.trim() || null;
      await updateBookmarkIconApi(item.id, {
        iconPadding: editPadding.value,
        iconBgColor: editBgColor.value || null,
        useHdLogo: editUseHdLogo.value,
        appName: nextAppName,
      });
      item.iconPadding = editPadding.value;
      item.iconBgColor = editBgColor.value || undefined;
      item.useHdLogo = editUseHdLogo.value;
      item.appName = nextAppName || undefined;
    }
    ElMessage.success("已保存");
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
            <BookmarkIcon
              :value="item"
              :size="72"
              :hd-url="item.useHdLogo ? item.logoUrl : undefined"
            />
            <div class="title">{{ displayName(item) }}</div>
          </div>
        </div>
        <ElEmpty v-else-if="!loading" description="暂无成功状态的书签" />
      </div>
    </ElCard>

    <ElDialog
      v-model="detailVisible"
      :title="detailItem ? detailItem.title || displayName(detailItem) : ''"
      width="980px"
      append-to-body
      class="icon-dialog"
    >
      <div v-if="detailItem" class="detail-body">
        <!-- 标题下方：网站域名小字 -->
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

        <!-- 左右结构：左侧预览 / 右侧编辑 -->
        <div class="detail-main">
          <!-- 预览：大中小三尺寸同显，随「编辑」区选择实时切换图标源 / 内边距 / 背景色 -->
          <div class="preview-pane">
            <div class="pane-title">预览</div>
            <div class="preview-area">
              <div class="preview-sizes">
                <div
                  v-for="s in PREVIEW_SIZES"
                  :key="s.value"
                  class="preview-size-item"
                >
                  <BookmarkIcon
                    :value="detailItem"
                    :size="s.value"
                    :padding="editPadding"
                    :bg-color="editBgColor ?? undefined"
                    :hd-url="editUseHdLogo ? detailItem.logoUrl : undefined"
                  />
                  <span class="preview-size-tag">{{ s.label }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 右侧：编辑区域 -->
          <div class="edit-pane">
            <div class="pane-title">编辑</div>
            <!-- 书签简称：手填 + DeepSeek 生成 -->
            <div class="edit-row">
              <span class="edit-label">书签简称</span>
              <div class="edit-control">
                <ElInput
                  v-model="editAppName"
                  placeholder="书签简称"
                  size="small"
                  class="appname-input"
                />
                <ElButton
                  size="small"
                  :loading="generatingAppName"
                  @click="generateAppName"
                >
                  DeepSeek 生成
                </ElButton>
              </div>
            </div>

            <!-- 选择图标源：小图 / 高清，各以图片展示；无高清 LOGO 时禁用高清 -->
            <div class="edit-row">
              <span class="edit-label">选择图标源</span>
              <div class="edit-control">
                <div class="source-options">
                  <div
                    :class="['source-option', { active: !editUseHdLogo }]"
                    @click="editUseHdLogo = false"
                  >
                    <div class="source-thumb">
                      <BookmarkIcon :value="detailItem" :size="48" />
                    </div>
                    <span class="source-tag">小图</span>
                  </div>
                  <div
                    :class="[
                      'source-option',
                      { active: editUseHdLogo, disabled: !detailItem.logoUrl },
                    ]"
                    @click="detailItem.logoUrl && (editUseHdLogo = true)"
                  >
                    <div class="source-thumb">
                      <img
                        v-if="detailItem.logoUrl"
                        :src="detailItem.logoUrl"
                        class="source-thumb-img"
                        alt="高清图标"
                        draggable="false"
                      />
                      <span v-else class="source-thumb-empty">无</span>
                    </div>
                    <span class="source-tag">高清</span>
                  </div>
                </div>
                <span v-if="!detailItem.logoUrl" class="edit-hint">
                  未获取到高清 LOGO
                </span>
              </div>
            </div>

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

        <!-- 重新获取结果：标题 / 图标 的旧-新对比选择 -->
        <div v-if="refetchResult" class="refetch-compare">
          <div class="pane-title">更新</div>
          <!-- 标题对比 -->
          <div class="compare-group">
            <span class="compare-label">标题</span>
            <div class="compare-options">
              <div
                :class="['compare-option', { active: chooseTitle === 'old' }]"
                @click="chooseTitle = 'old'"
              >
                <span class="compare-tag">旧</span>
                <span class="compare-text">{{ detailItem.title || "（空）" }}</span>
              </div>
              <div
                :class="['compare-option', { active: chooseTitle === 'new' }]"
                @click="chooseTitle = 'new'"
              >
                <span class="compare-tag is-new">新</span>
                <span class="compare-text">{{
                  refetchResult.title || "（空）"
                }}</span>
              </div>
            </div>
          </div>

          <!-- 小图标对比：独立选择旧 / 新 -->
          <div class="compare-group">
            <span class="compare-label">小图标</span>
            <div class="compare-options">
              <div
                :class="[
                  'compare-option icon-option',
                  { active: chooseIcon === 'old' },
                ]"
                @click="chooseIcon = 'old'"
              >
                <BookmarkIcon :value="detailItem" :size="52" />
                <span class="compare-tag">旧</span>
              </div>
              <div
                :class="[
                  'compare-option icon-option',
                  { active: chooseIcon === 'new' },
                ]"
                @click="chooseIcon = 'new'"
              >
                <BookmarkIcon
                  :value="{ ...detailItem, iconBase64: refetchResult.iconBase64 }"
                  :size="52"
                />
                <span class="compare-tag is-new">新</span>
              </div>
            </div>
          </div>

          <!-- 大图标(高清 LOGO)对比：独立选择旧 / 新 -->
          <div class="compare-group">
            <span class="compare-label">大图标</span>
            <div class="compare-options">
              <div
                :class="[
                  'compare-option icon-option',
                  { active: chooseLogo === 'old' },
                ]"
                @click="chooseLogo = 'old'"
              >
                <div class="logo-thumb">
                  <img
                    v-if="detailItem.logoUrl && !oldLogoError"
                    :src="detailItem.logoUrl"
                    class="logo-thumb-img"
                    alt="旧高清 LOGO"
                    draggable="false"
                    @error="oldLogoError = true"
                  />
                  <span v-else class="logo-thumb-empty">无</span>
                </div>
                <span class="compare-tag">旧</span>
              </div>
              <div
                :class="[
                  'compare-option icon-option',
                  { active: chooseLogo === 'new' },
                ]"
                @click="chooseLogo = 'new'"
              >
                <div class="logo-thumb">
                  <img
                    v-if="refetchResult.logoUrl && !newLogoError"
                    :src="refetchResult.logoUrl"
                    class="logo-thumb-img"
                    alt="新高清 LOGO"
                    draggable="false"
                    @error="newLogoError = true"
                  />
                  <span v-else class="logo-thumb-empty">无</span>
                </div>
                <span class="compare-tag is-new">新</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <ElButton :loading="refetching" @click="refetch">重新获取</ElButton>
        <ElButton
          type="primary"
          :loading="savingIcon"
          :disabled="!canSave"
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

/* 上下结构：预览在上、编辑在下 */
.detail-main {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: stretch;
}

/* 预览面板（灰底卡片，与下方编辑区明显区分） */
.preview-pane {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  padding: 16px;
  background: var(--el-fill-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
}

.preview-area {
  display: flex;
  width: 100%;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  min-height: 180px;
  padding: 14px 8px;
  background: var(--el-bg-color);
  border-radius: 8px;
}

/* 三尺寸并排：底部对齐，大图最高 */
.preview-sizes {
  display: flex;
  gap: 16px;
  align-items: flex-end;
  justify-content: center;
}

.preview-size-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
}

.preview-size-tag {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

/* 编辑面板（白底卡片） */
.edit-pane {
  display: flex;
  flex: none;
  flex-direction: column;
  justify-content: flex-start;
  gap: 18px;
  min-width: 0;
  padding: 16px 18px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
}

/* 各区域标题（预览 / 编辑 / 更新） */
.pane-title {
  width: 100%;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  text-align: left;
}

.edit-row {
  display: flex;
  flex-direction: row;
  gap: 12px;
  align-items: center;
}

.edit-label {
  flex: 0 0 72px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.edit-control {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.appname-input {
  flex: 1;
  min-width: 0;
}

.edit-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 图标源选择：小图 / 高清，图片化两选一 */
.source-options {
  display: flex;
  gap: 12px;
}

.source-option {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.source-option:hover {
  border-color: var(--el-color-primary-light-5);
}

.source-option.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}

.source-option.disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.source-option.disabled:hover {
  border-color: var(--el-border-color);
}

.source-thumb {
  display: flex;
  width: 52px;
  height: 52px;
  align-items: center;
  justify-content: center;
  padding: 2px;
}

.source-thumb-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}

.source-thumb-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.source-tag {
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.source-option.active .source-tag {
  font-weight: 600;
  color: var(--el-color-primary);
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

/* 重新获取：旧-新对比选择区 */
.refetch-compare {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 14px 16px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 12px;
}

.compare-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.compare-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.compare-options {
  display: flex;
  gap: 12px;
}

.compare-option {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.compare-option:hover {
  border-color: var(--el-color-primary-light-5);
}

.compare-option.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary) inset;
}

/* 图标对比项：纵向排列，居中且不撑满 */
.compare-option.icon-option {
  flex: 0 0 auto;
  flex-direction: column;
  gap: 8px;
}

/* 大图标（高清 LOGO）缩略框：与小图标尺寸相近 */
.logo-thumb {
  display: flex;
  width: 52px;
  height: 52px;
  align-items: center;
  justify-content: center;
  padding: 4px;
  background: var(--el-fill-color-light);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}

.logo-thumb-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}

.logo-thumb-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.compare-tag {
  flex: none;
  padding: 0 6px;
  font-size: 11px;
  line-height: 18px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
  border-radius: 4px;
}

.compare-tag.is-new {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.compare-text {
  overflow: hidden;
  font-size: 13px;
  color: var(--el-text-color-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 标题下方：网站域名小字 */
.domain-text {
  padding-bottom: 12px;
  margin-top: -2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: left;
  border-bottom: 1px solid var(--el-border-color-lighter);
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
