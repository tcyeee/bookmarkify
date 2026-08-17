<script lang="ts" setup>
/**
 * 书签清理：删掉已经没有任何用户收藏、且再也不会有内容的页面与站点。
 *
 * ## 为什么按钮点下去不直接删
 *
 * 这是这一页里唯一一个**没有撤销路径**的操作 —— 其余分类改的都是数字，改错了改回来即可。
 * 所以点击先拉一次预览，把「这一轮会删掉哪些、各多少条」摆出来，确认之后才真删；
 * 预览与执行在服务端是同一段判定代码，确认框里的数字才谈得上是承诺。
 *
 * 页面与站点的删除数单独一行、附属行（元信息/快照/探测日志…）折在下面：后者数量大一个数量级
 * （一个页面能带几十条探测日志），并排列会让人误以为删多了。
 */
import type { OrphanCleanupReport } from '#/api/bookmark-cleanup';

import { computed, ref } from 'vue';

import { ElAlert, ElButton, ElDialog, ElMessage } from '#/adapter/element';
import {
  previewBookmarkCleanupApi,
  runBookmarkCleanupApi,
} from '#/api/bookmark-cleanup';

const visible = ref(false);
const preview = ref<null | OrphanCleanupReport>(null);
const result = ref<null | OrphanCleanupReport>(null);
const loading = ref(false);
const submitting = ref(false);
/** 预览拉取失败的原因。失败时**不允许**执行：看不见范围就删，正是这个弹窗要防的事 */
const loadError = ref('');

/** 级联清掉的附属行，按「一个页面/站点带出来的东西」排列 */
const cascadeRows = computed(() => {
  const r = preview.value;
  if (!r) return [];
  return [
    { label: '页面元信息', value: r.pageMeta },
    { label: '抓取快照', value: r.snapshots },
    { label: '探测日志', value: r.pingLogs },
    { label: '分类关联', value: r.pageCategories },
    { label: '页面级图片(社交图/截图)', value: r.pageAssets },
    { label: '站点级图片(favicon/logo)', value: r.siteAssets },
  ].filter((row) => row.value > 0);
});

const nothingToDo = computed(
  () => !!preview.value && preview.value.pages === 0 && preview.value.sites === 0,
);

async function openDialog() {
  visible.value = true;
  loading.value = true;
  loadError.value = '';
  preview.value = null;
  try {
    preview.value = await previewBookmarkCleanupApi();
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '预览加载失败';
  } finally {
    loading.value = false;
  }
}

async function submit() {
  submitting.value = true;
  try {
    result.value = await runBookmarkCleanupApi();
    visible.value = false;
    ElMessage.success(
      `已清理 ${result.value.pages} 个页面、${result.value.sites} 个站点`,
    );
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div>
    <!-- 规则写在按钮上方而不是收进 tooltip：这一项没有"当前值"可看，
         点之前唯一要读懂的就是这两条判据 -->
    <div class="text-muted-foreground space-y-3 pt-1 text-sm leading-relaxed">
      <p class="m-0">
        页面与站点是所有用户共享的抓取产物，不随某个用户删书签而消失 ——
        于是「最后一个收藏它的人删掉了书签」这件事在库里没有出口，记录只增不减。
        这里把其中确定不会再有价值的两类删掉：
      </p>
      <ul class="m-0 space-y-2 pl-5">
        <li>
          <span class="text-foreground font-medium">无人引用的本地地址 / IP 站点</span> ——
          这类地址从来就不抓取，标题、图标、元信息、巡检游标永远是空的。
        </li>
        <li>
          <span class="text-foreground font-medium">无人引用且已判定失活的页面</span> ——
          「抓取失败」与「已归档」两种状态。判失活本身要连续失败到配置的次数才落定，
          归档更是按退避曲线累计两个多月，走到这一步的记录已被反复确认打不开。
        </li>
      </ul>
      <p class="m-0">
        名下页面被删光、且自身也是本地/IP 或已判定不可达的站点，一并删除。
        还有任何用户收藏的记录一律不动；刚创建 10 分钟内的页面也不动 ——
        用户正在添加的书签会先建页面、再建关联，那段窗口里它看起来正是「无人引用」。
      </p>
    </div>

    <div class="mt-6 flex items-center gap-3">
      <ElButton type="danger" @click="openDialog">清理无人引用书签</ElButton>
      <span v-if="result" class="text-muted-foreground text-xs">
        上次清理：页面 {{ result.pages }} 个、站点 {{ result.sites }} 个，
        耗时 {{ result.durationMs }} ms
      </span>
    </div>

    <ElDialog v-model="visible" title="清理无人引用书签" width="560px">
      <div class="space-y-4 text-sm">
        <div v-if="loading" class="py-6 text-center text-gray-400">
          正在统计本轮范围…
        </div>

        <ElAlert
          v-else-if="loadError"
          :closable="false"
          :description="`${loadError}。看不到本轮范围时不允许执行。`"
          show-icon
          title="预览加载失败"
          type="error"
        />

        <template v-else-if="preview">
          <ElAlert
            v-if="nothingToDo"
            :closable="false"
            description="当前没有符合条件的页面或站点，执行也不会改动任何数据。"
            show-icon
            title="没有可清理的记录"
            type="success"
          />
          <ElAlert
            v-else
            :closable="false"
            description="删除不可撤销。下面每个数字都是此刻现算的，与真正执行时用的是同一套判定。"
            show-icon
            title="这一轮会永久删除以下记录"
            type="warning"
          />

          <div class="divide-y rounded border">
            <div class="flex items-baseline gap-3 px-3 py-2">
              <span class="w-20 shrink-0 text-gray-500">页面</span>
              <span>
                <span class="font-medium">{{ preview.pages }}</span> 条
                <span class="text-xs text-gray-400">
                  （本地/IP {{ preview.localIpPages }} 条，已失活
                  {{ preview.deadPages }} 条；两者会重叠，故不等于相加）
                </span>
              </span>
            </div>

            <div class="flex items-baseline gap-3 px-3 py-2">
              <span class="w-20 shrink-0 text-gray-500">站点</span>
              <span>
                <span class="font-medium">{{ preview.sites }}</span> 条
                <span class="text-xs text-gray-400">
                  （名下页面已被删光，且本身是本地/IP 或已判定不可达）
                </span>
              </span>
            </div>

            <div
              v-if="preview.skippedRecentPages > 0"
              class="flex items-baseline gap-3 px-3 py-2"
            >
              <span class="w-20 shrink-0 text-gray-500">本轮跳过</span>
              <span class="text-xs text-gray-500">
                {{ preview.skippedRecentPages }} 条命中规则但创建不足 10
                分钟，可能正在被添加，留到下一轮
              </span>
            </div>

            <div
              v-if="cascadeRows.length > 0"
              class="flex items-baseline gap-3 px-3 py-2"
            >
              <span class="w-20 shrink-0 text-gray-500">连带清理</span>
              <span class="text-xs text-gray-500">
                <template v-for="(row, index) in cascadeRows" :key="row.label">
                  <span v-if="index > 0">、</span>{{ row.label }}
                  {{ row.value }} 条
                </template>
              </span>
            </div>

            <div
              v-if="preview.releasedFiles > 0"
              class="flex items-baseline gap-3 px-3 py-2"
            >
              <span class="w-20 shrink-0 text-gray-500">对象存储</span>
              <span class="text-xs text-gray-500">
                {{ preview.releasedFiles }} 个文件将失去引用。<span
                  class="text-foreground font-medium"
                  >本操作不删对象</span
                >，它们由下一轮 OSS 对账认定为孤儿后按既有回收策略处理
              </span>
            </div>
          </div>
        </template>
      </div>

      <template #footer>
        <ElButton @click="visible = false">取消</ElButton>
        <ElButton
          :disabled="!preview || nothingToDo || loading"
          :loading="submitting"
          type="danger"
          @click="submit"
        >
          确认清理
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>
