<script lang="ts" setup>
import type { CollectionBookmark } from '#/api/systemCollection';
import type {
  CollectionCrawlStatus,
  CollectionCrawlUpdate,
  CollectionSocketHandle,
} from '#/api/systemCollectionSocket';

import { computed, onBeforeUnmount, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElInput,
  ElMessage,
  ElProgress,
  ElStep,
  ElSteps,
  ElTag,
} from 'element-plus';

import BookmarkIcon from '#/views/bookmark/BookmarkIcon.vue';
import {
  extractCollectionLinksApi,
  generateCollectionMetaApi,
  publishSystemCollectionApi,
  startCollectionCrawlApi,
} from '#/api/systemCollection';
import { createSystemCollectionSocket } from '#/api/systemCollectionSocket';

type Step = 1 | 2 | 3;
type LinkStatus = 'CRAWLING' | 'FAILED' | 'SUCCESS' | 'WAITING';

interface RawLinkItem {
  id: string;
  rawUrl: string;
  status: LinkStatus;
  bookmark?: CollectionBookmark;
  errorMsg?: string;
}

const publishing = ref(false);
const activeStep = ref<Step>(1);
const sourceText = ref('');
const extracting = ref(false);
const crawling = ref(false);
const generatingMeta = ref(false);
const publishingCollection = ref(false);
const jobId = ref('');
const items = ref<RawLinkItem[]>([]);
const collectionTitle = ref('');
const collectionDescription = ref('');
let crawlSocket: CollectionSocketHandle | null = null;

const successfulItems = computed(() => items.value.filter((item) => item.status === 'SUCCESS'));
const failedItems = computed(() => items.value.filter((item) => item.status === 'FAILED'));
const finishedCount = computed(() =>
  items.value.filter((item) => item.status === 'SUCCESS' || item.status === 'FAILED').length,
);
const crawlProgress = computed(() =>
  items.value.length ? Math.round((finishedCount.value / items.value.length) * 100) : 0,
);
const crawlFinished = computed(
  () => items.value.length > 0 && finishedCount.value === items.value.length,
);

function startPublishing() {
  publishing.value = true;
  activeStep.value = 1;
}

function closePublishing() {
  closeCrawlSocket();
  publishing.value = false;
  activeStep.value = 1;
  sourceText.value = '';
  items.value = [];
  jobId.value = '';
  collectionTitle.value = '';
  collectionDescription.value = '';
}

async function extractLinks() {
  if (!sourceText.value.trim()) {
    ElMessage.warning('请先粘贴包含链接的文本');
    return;
  }

  extracting.value = true;
  try {
    const result = await extractCollectionLinksApi(sourceText.value.trim());
    const uniqueLinks = [...new Set(result.links.map((url) => url.trim()).filter(Boolean))];
    if (!uniqueLinks.length) {
      ElMessage.warning('AI 没有从文本中识别出可用链接');
      return;
    }
    items.value = uniqueLinks.map((rawUrl, index) => ({
      id: `${index}-${rawUrl}`,
      rawUrl,
      status: 'WAITING',
    }));
    activeStep.value = 2;
  } catch {
    // requestClient 已负责展示服务端错误，这里只恢复按钮状态。
  } finally {
    extracting.value = false;
  }
}

function closeCrawlSocket() {
  crawlSocket?.close();
  crawlSocket = null;
}

function normalizeStatus(status: CollectionCrawlStatus): LinkStatus {
  return status === 'CRAWLING' || status === 'SUCCESS' || status === 'FAILED'
    ? status
    : 'FAILED';
}

function handleCrawlUpdate(update: CollectionCrawlUpdate) {
  if (jobId.value && update.jobId !== jobId.value) return;
  const item = items.value.find((candidate) => candidate.rawUrl === update.rawUrl);
  if (!item) return;
  item.status = normalizeStatus(update.status);
  item.bookmark = update.bookmark;
  item.errorMsg = update.errorMsg;
  if (crawlFinished.value) {
    closeCrawlSocket();
    void prepareMeta();
  }
}

async function startCrawling() {
  if (!items.value.length || crawling.value) return;
  crawling.value = true;
  jobId.value = '';
  items.value.forEach((item) => {
    item.status = 'WAITING';
    item.errorMsg = undefined;
  });
  closeCrawlSocket();
  crawlSocket = createSystemCollectionSocket(handleCrawlUpdate);
  try {
    const result = await startCollectionCrawlApi(items.value.map((item) => item.rawUrl));
    jobId.value = result.jobId;
    // 极快完成的任务可能在 HTTP 响应返回前已经推送了 SUCCESS/FAILED，不能把这些终态
    // 又覆盖回「抓取中」；只更新仍在等待的条目。
    items.value.forEach((item) => {
      if (item.status === 'WAITING') item.status = 'CRAWLING';
    });
  } catch {
    closeCrawlSocket();
    items.value.forEach((item) => {
      item.status = 'WAITING';
    });
  } finally {
    crawling.value = false;
  }
}

async function prepareMeta() {
  if (!successfulItems.value.length) {
    ElMessage.warning('没有成功抓取的书签，无法生成集合');
    return;
  }
  generatingMeta.value = true;
  try {
    const result = await generateCollectionMetaApi(
      successfulItems.value.map((item) => item.bookmark).filter(Boolean) as CollectionBookmark[],
    );
    collectionTitle.value = result.title;
    collectionDescription.value = result.description;
    activeStep.value = 3;
  } catch {
    // requestClient 已展示错误。
  } finally {
    generatingMeta.value = false;
  }
}

async function publishCollection() {
  if (!collectionTitle.value.trim()) {
    ElMessage.warning('请填写集合标题');
    return;
  }
  if (!successfulItems.value.length) {
    ElMessage.warning('至少需要一条抓取成功的书签');
    return;
  }
  publishingCollection.value = true;
  try {
    await publishSystemCollectionApi({
      title: collectionTitle.value.trim(),
      description: collectionDescription.value.trim(),
      pageIds: successfulItems.value
        .map((item) => item.bookmark?.pageId)
        .filter((pageId): pageId is string => Boolean(pageId)),
    });
    ElMessage.success('系统书签集发布成功');
    closePublishing();
  } catch {
    // requestClient 已展示错误。
  } finally {
    publishingCollection.value = false;
  }
}

function statusLabel(status: LinkStatus) {
  return {
    CRAWLING: '抓取中',
    FAILED: '抓取失败',
    SUCCESS: '已完成',
    WAITING: '等待抓取',
  }[status];
}

function statusType(status: LinkStatus) {
  return {
    CRAWLING: 'warning',
    FAILED: 'danger',
    SUCCESS: 'success',
    WAITING: 'info',
  }[status] as 'danger' | 'info' | 'success' | 'warning';
}

function shortUrl(url: string) {
  return url.length > 92 ? `${url.slice(0, 62)}…${url.slice(-24)}` : url;
}

onBeforeUnmount(closeCrawlSocket);
</script>

<template>
  <Page auto-content-height>
    <div class="collection-page">
      <ElCard v-if="!publishing" shadow="never" class="intro-card">
        <div class="intro-content">
          <div>
            <div class="eyebrow">SYSTEM COLLECTIONS</div>
            <h1>系统书签集</h1>
            <p>把散落在文章、聊天记录和网页中的链接，整理成一组可发布的书签。</p>
          </div>
          <ElButton type="primary" size="large" @click="startPublishing">
            AI 批量导出为集合
          </ElButton>
        </div>
      </ElCard>

      <template v-else>
        <div class="workflow-header">
          <div>
            <div class="eyebrow">PUBLISH WORKFLOW</div>
            <h1>AI 批量导出为集合</h1>
          </div>
          <ElButton text @click="closePublishing">退出发布</ElButton>
        </div>

        <ElSteps :active="activeStep - 1" finish-status="success" class="steps">
          <ElStep title="提取链接" description="AI 识别文本中的原始链接" />
          <ElStep title="抓取书签" description="实时查看抓取进度" />
          <ElStep title="编辑并发布" description="确认集合信息后发布" />
        </ElSteps>

        <ElCard v-if="activeStep === 1" shadow="never" class="step-card">
          <div class="card-heading">
            <div>
              <h2>粘贴链接文本</h2>
              <p>支持直接粘贴文章、Markdown、聊天记录或浏览器导出的长文本，AI 会自动提取其中的链接并去重。</p>
            </div>
            <ElTag type="info">步骤 1 / 3</ElTag>
          </div>
          <ElInput
            v-model="sourceText"
            type="textarea"
            :autosize="{ minRows: 13, maxRows: 24 }"
            maxlength="100000"
            show-word-limit
            resize="vertical"
            placeholder="例如：\n今天看到一篇很棒的文章 https://example.com/article\n工具地址：https://bookmarkify.cc\n也支持带有 Markdown 链接的文本 [OpenAI](https://openai.com)" />
          <div class="step-actions">
            <ElButton type="primary" :loading="extracting" @click="extractLinks">
              提交给 AI，提取链接
            </ElButton>
          </div>
        </ElCard>

        <ElCard v-else-if="activeStep === 2" shadow="never" class="step-card">
          <div class="card-heading">
            <div>
              <h2>确认并抓取链接</h2>
              <p>共识别 {{ items.length }} 条原始链接。点击开始抓取后，状态会通过 WebSocket 实时更新。</p>
            </div>
            <ElTag type="info">步骤 2 / 3</ElTag>
          </div>

          <div v-if="crawling || jobId" class="progress-panel">
            <div class="progress-meta">
              <span>整体抓取进度</span>
              <strong>{{ finishedCount }} / {{ items.length }}</strong>
            </div>
            <ElProgress :percentage="crawlProgress" :status="crawlFinished ? 'success' : undefined" />
          </div>

          <ElAlert
            v-if="failedItems.length"
            type="warning"
            :closable="false"
            class="mb-4"
            :title="`${failedItems.length} 条链接抓取失败，发布时将自动跳过`" />

          <div class="raw-link-list">
            <div v-for="item in items" :key="item.id" class="raw-link-item">
              <BookmarkIcon :src="item.bookmark?.iconUrl ?? undefined" :size="42" />
              <div class="raw-link-body">
                <div v-if="item.bookmark" class="bookmark-title">{{ item.bookmark.title || '（无标题）' }}</div>
                <div class="raw-link-url" :title="item.rawUrl">{{ shortUrl(item.rawUrl) }}</div>
                <div v-if="item.bookmark?.description" class="bookmark-description">
                  {{ item.bookmark.description }}
                </div>
                <div v-if="item.errorMsg" class="error-text">{{ item.errorMsg }}</div>
              </div>
              <ElTag :type="statusType(item.status)" size="small">{{ statusLabel(item.status) }}</ElTag>
            </div>
          </div>

          <div class="step-actions">
            <ElButton @click="activeStep = 1">返回上一步</ElButton>
            <ElButton
              type="primary"
              :loading="crawling"
              :disabled="crawling || crawlFinished"
              @click="startCrawling">
              {{ crawlFinished ? '抓取已完成' : '开始抓取' }}
            </ElButton>
            <ElButton
              v-if="crawlFinished"
              type="success"
              :loading="generatingMeta"
              :disabled="!successfulItems.length"
              @click="prepareMeta">
              下一步：生成集合信息
            </ElButton>
          </div>
        </ElCard>

        <ElCard v-else shadow="never" class="step-card">
          <div class="card-heading">
            <div>
              <h2>编辑集合信息</h2>
              <p>AI 已根据抓取成功的 {{ successfulItems.length }} 条书签生成建议，你可以在发布前修改。</p>
            </div>
            <ElTag type="info">步骤 3 / 3</ElTag>
          </div>

          <div class="meta-form">
            <label>集合标题</label>
            <ElInput v-model="collectionTitle" maxlength="80" show-word-limit placeholder="请输入书签集标题" />
            <label>集合描述</label>
            <ElInput
              v-model="collectionDescription"
              type="textarea"
              :autosize="{ minRows: 5, maxRows: 10 }"
              maxlength="500"
              show-word-limit
              placeholder="请输入书签集描述" />
          </div>

          <div class="section-heading">
            <h3>包含的书签</h3>
            <span>{{ successfulItems.length }} 条</span>
          </div>
          <div class="bookmark-grid">
            <div v-for="item in successfulItems" :key="item.id" class="bookmark-card">
              <BookmarkIcon :src="item.bookmark?.iconUrl ?? undefined" :size="48" />
              <div class="bookmark-body">
                <div class="bookmark-title">{{ item.bookmark?.title || '（无标题）' }}</div>
                <div class="raw-link-url" :title="item.rawUrl">{{ shortUrl(item.rawUrl) }}</div>
              </div>
            </div>
          </div>

          <div class="step-actions">
            <ElButton @click="activeStep = 2">返回上一步</ElButton>
            <ElButton type="primary" :loading="publishingCollection" @click="publishCollection">
              发布为正式书签集
            </ElButton>
          </div>
        </ElCard>
      </template>

      <ElEmpty v-if="!publishing" description="还没有发布中的系统书签集" class="empty-state" />
    </div>
  </Page>
</template>

<style scoped>
.collection-page {
  min-height: 100%;
  padding: 4px 4px 28px;
}

.intro-card {
  border: 0;
  background: linear-gradient(135deg, #eef5ff 0%, #f8fbff 55%, #ffffff 100%);
}

.intro-content,
.workflow-header,
.card-heading,
.progress-meta,
.step-actions,
.section-heading,
.raw-link-item,
.bookmark-card {
  display: flex;
  align-items: center;
}

.intro-content {
  justify-content: space-between;
  gap: 24px;
  padding: 30px 26px;
}

.eyebrow {
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

h1,
h2,
h3,
p {
  margin: 0;
}

h1 {
  margin-top: 8px;
  color: var(--el-text-color-primary);
  font-size: 25px;
}

.intro-content p,
.card-heading p {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.empty-state {
  min-height: 340px;
}

.workflow-header {
  justify-content: space-between;
  margin: 0 4px 20px;
}

.workflow-header h1 {
  font-size: 22px;
}

.steps {
  max-width: 900px;
  margin: 0 auto 24px;
}

.step-card {
  max-width: 1040px;
  margin: 0 auto;
}

.card-heading {
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 22px;
}

.card-heading h2 {
  font-size: 18px;
}

.step-actions {
  justify-content: flex-end;
  gap: 10px;
  margin-top: 22px;
}

.progress-panel {
  padding: 14px 16px 10px;
  margin-bottom: 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}

.progress-meta {
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.progress-meta strong {
  color: var(--el-text-color-primary);
}

.raw-link-list {
  max-height: 510px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.raw-link-item {
  gap: 13px;
  padding: 12px 15px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.raw-link-item:last-child {
  border-bottom: 0;
}

.raw-link-body,
.bookmark-body {
  min-width: 0;
  flex: 1;
}

.bookmark-title {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.raw-link-url {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bookmark-description {
  margin-top: 4px;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.error-text {
  margin-top: 4px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.meta-form {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.meta-form label {
  padding-top: 9px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.section-heading {
  gap: 10px;
  margin: 28px 0 12px;
}

.section-heading h3 {
  font-size: 15px;
}

.section-heading span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.bookmark-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(270px, 1fr));
  gap: 10px;
  max-height: 360px;
  overflow: auto;
}

.bookmark-card {
  min-width: 0;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

@media (max-width: 640px) {
  .intro-content {
    flex-direction: column;
    align-items: flex-start;
  }

  .intro-content .el-button {
    width: 100%;
  }

  .meta-form {
    grid-template-columns: 1fr;
    gap: 6px;
  }

  .meta-form label {
    padding-top: 8px;
  }

  .step-actions {
    flex-wrap: wrap;
  }
}
</style>
