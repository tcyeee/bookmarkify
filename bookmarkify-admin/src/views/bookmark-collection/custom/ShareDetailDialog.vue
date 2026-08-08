<script lang="ts" setup>
import type { ShareDetailVO, UserShareAdminVO } from "#/api/share";

import { computed, defineAsyncComponent, ref, watch } from "vue";

import { formatDateTime } from "@vben/utils";

import { ElMessage } from "element-plus";

import { getAdminShareDetailApi } from "#/api/share";
import BookmarkIcon from "#/views/bookmark/BookmarkIcon.vue";

import { shareStatusMeta } from "./shareStatus";

/**
 * 用户自定义书签集详情弹窗。
 *
 * 列表页只有「谁分享了几条」，而审核要回答的是「分享里到底放了什么」——所以这里的主体是
 * 书签本身：图标、标题、完整地址、以及站点级的 NSFW 判定（分享被驳回时的唯一依据）。
 *
 * 取数刻意走管理端专用的 `/admin/share/detail`：公开视图 `/share/view` 会校验状态，
 * 而后台最需要打开的恰恰是被下架、未过审的那几条。
 */
const props = defineProps<{
  /** 列表行；弹窗先用它渲染头部，详情返回后再以服务端数据为准 */
  row?: null | UserShareAdminVO;
}>();

const emit = defineEmits<{
  /** 请求强制下架，交由列表页统一二次确认并刷新 */
  (e: "takedown", row: UserShareAdminVO): void;
}>();

const visible = defineModel<boolean>({ default: false });

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/dialog/index"),
    import("element-plus/es/components/dialog/style/css"),
  ]).then(([res]) => res.ElDialog),
);
const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag),
);
const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton),
);

const loading = ref(false);
const detail = ref<null | ShareDetailVO>(null);
const notFound = ref(false);
/** 请求失败。与 notFound 是两件事：一个说"这条不存在"，一个说"没问出来，再试一次" */
const loadError = ref("");

/**
 * 请求序号。弹窗不销毁、只换 `props.row`，于是「点 A（慢查询）→ 关掉 → 点 B」会有两个
 * 请求在飞；B 先回、A 后回时 A 会覆盖 `detail`，而头部用的是 `props.row`（B）——
 * 表头是 B、书签列表是 A。审核界面据此决定下不下架，串行显示的后果不是难看而是误判。
 */
let loadSeq = 0;

/** 头部信息：详情到手前先用列表行顶上，避免弹窗打开瞬间是一片空白 */
const share = computed<null | UserShareAdminVO>(() => detail.value?.share ?? props.row ?? null);
const statusTag = computed(() => shareStatusMeta(share.value?.status));
const bookmarks = computed(() => detail.value?.bookmarks ?? []);
const shareUrl = computed(() => (share.value ? `https://bookmarkify.cc/share/${share.value.id}` : ""));

async function load(id: string) {
  const seq = ++loadSeq;
  loading.value = true;
  notFound.value = false;
  loadError.value = "";
  detail.value = null;
  try {
    const res = await getAdminShareDetailApi(id);
    if (seq !== loadSeq) return;
    if (res) {
      detail.value = res;
    } else {
      // 后端对「查不到」返回 data: null 而非 404，这里要显式区分于请求失败
      notFound.value = true;
    }
  } catch (error) {
    if (seq !== loadSeq) return;
    // 没有这一支时，请求失败会一路落到模板最后那条 v-else-if，把一次 500 显示成
    // 「这个书签集里没有书签」——审核员据此认为分享是空的，恰好是最坏的一种误报
    loadError.value = error instanceof Error ? error.message : "请求失败";
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

watch(
  () => [visible.value, props.row?.id] as const,
  ([open, id]) => {
    // 关闭时也要推进序号：在飞的那个请求回来时不该再往一个已经关掉的弹窗里写数据
    if (open && id) void load(id);
    else loadSeq++;
  },
  { immediate: true },
);

function reload() {
  if (props.row?.id) void load(props.row.id);
}

async function copyShareUrl() {
  try {
    await navigator.clipboard.writeText(shareUrl.value);
    ElMessage.success("分享链接已复制");
  } catch {
    ElMessage.warning("复制失败，请手动选中链接");
  }
}

function handleTakeDown() {
  if (share.value) emit("takedown", share.value);
}

/** 地址过长时中间省略，保留 host 与结尾路径——两头才是判断这条书签是什么的信息 */
function shortUrl(url?: string) {
  if (!url) return "-";
  return url.length > 72 ? `${url.slice(0, 46)}…${url.slice(-20)}` : url;
}
</script>

<template>
  <ElDialog v-model="visible" title="书签集详情" width="900px" top="6vh" destroy-on-close>
    <div v-if="share" class="share-detail">
      <!-- ── 分享本身 ─────────────────────────────────────────────────────── -->
      <div class="meta-grid">
        <div class="meta-item">
          <span class="meta-label">分享人</span>
          <span class="meta-value">
            {{ share.nickName }}
            <span class="text-xs text-gray-400">({{ share.uid }})</span>
          </span>
        </div>
        <div class="meta-item">
          <span class="meta-label">状态</span>
          <span class="meta-value">
            <ElTag :type="statusTag.type" size="small">{{ statusTag.label }}</ElTag>
          </span>
        </div>
        <div class="meta-item">
          <span class="meta-label">创建时间</span>
          <span class="meta-value">{{ formatDateTime(share.createTime) }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">过期时间</span>
          <span class="meta-value">
            {{ share.expireTime ? formatDateTime(share.expireTime) : "永不过期" }}
          </span>
        </div>
        <div class="meta-item meta-item--wide">
          <span class="meta-label">文案</span>
          <span class="meta-value">{{ share.note || "-" }}</span>
        </div>
        <div v-if="share.rejectReason" class="meta-item meta-item--wide">
          <span class="meta-label">提示信息</span>
          <span class="meta-value text-orange-500">{{ share.rejectReason }}</span>
        </div>
        <div class="meta-item meta-item--wide">
          <span class="meta-label">分享链接</span>
          <span class="meta-value">
            <a :href="shareUrl" target="_blank" rel="noreferrer" class="link">{{ shareUrl }}</a>
            <ElButton link type="primary" class="ml-2" @click="copyShareUrl">复制</ElButton>
          </span>
        </div>
      </div>

      <!-- ── 分享包含的书签 ───────────────────────────────────────────────── -->
      <div class="section-title">
        包含的书签
        <span class="text-xs text-gray-400">共 {{ share.bookmarkCount }} 条</span>
      </div>

      <div v-if="loading" class="hint">加载中…</div>
      <div v-else-if="notFound" class="hint">该分享不存在或已被删除</div>
      <div v-else-if="loadError" class="hint hint--error">
        详情加载失败：{{ loadError }}
        <ElButton link type="primary" class="ml-2" @click="reload">重试</ElButton>
      </div>
      <div v-else-if="bookmarks.length === 0" class="hint">这个书签集里没有书签</div>
      <div v-else class="bookmark-list">
        <div v-for="item in bookmarks" :key="item.bookmarkId" class="bookmark-item">
          <BookmarkIcon :src="item.iconUrl ?? undefined" :size="44" />
          <div class="bookmark-body">
            <div class="bookmark-title">
              {{ item.title || "(无标题)" }}
              <ElTag v-if="item.nsfw" type="danger" size="small" class="ml-1">疑似违规</ElTag>
              <ElTag v-if="item.linkType && item.linkType !== 'DOMAIN'" type="warning" size="small" class="ml-1">
                {{ item.linkType }}
              </ElTag>
            </div>
            <a
              v-if="item.urlFull"
              :href="item.urlFull"
              target="_blank"
              rel="noreferrer"
              class="bookmark-url link"
              :title="item.urlFull">
              {{ shortUrl(item.urlFull) }}
            </a>
            <div v-if="item.description" class="bookmark-desc" :title="item.description">
              {{ item.description }}
            </div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <ElButton
        v-if="share"
        type="danger"
        :disabled="share.status === 'ADMIN_TAKEDOWN'"
        @click="handleTakeDown">
        强制下架
      </ElButton>
      <ElButton @click="visible = false">关闭</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.meta-item {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 22px;
}

.meta-item--wide {
  grid-column: 1 / -1;
}

.meta-label {
  flex: none;
  width: 64px;
  color: var(--el-text-color-secondary);
}

.meta-value {
  min-width: 0;
  word-break: break-all;
}

.section-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 14px 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.hint {
  padding: 24px 0;
  color: var(--el-text-color-secondary);
  text-align: center;
}

.hint--error {
  color: var(--el-color-danger);
}

.bookmark-list {
  display: grid;
  max-height: 52vh;
  overflow-y: auto;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.bookmark-item {
  display: flex;
  gap: 10px;
  padding: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.bookmark-body {
  min-width: 0;
  flex: 1;
}

.bookmark-title {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bookmark-url,
.bookmark-desc {
  display: block;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bookmark-desc {
  color: var(--el-text-color-secondary);
}

.link {
  color: var(--el-color-primary);
}

.link:hover {
  text-decoration: underline;
}
</style>
