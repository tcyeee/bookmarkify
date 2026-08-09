<script lang="ts" setup>
/**
 * 活性巡检轮次明细：一轮一行。
 *
 * 与「Scrapper 调用日志」「书签活性日志」的分工：那两张表分别是一次 HTTP 调用一行、
 * 一次探测一行，都回答不了「巡检系统本身怎么样」——有没有被熔断、积压追不追得上、
 * 有多少重新抓取因为解析队列拥堵被推迟。这三个数只有按轮聚合才看得出走势。
 */
import type { BookmarkSweepLogVO } from '#/api/bookmark-sweep-log';

import { defineAsyncComponent, onUnmounted, reactive, ref } from 'vue';

import { useRoute } from 'vue-router';

import { Page } from '@vben/common-ui';
import { CircleHelp } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { ElButton, ElMessage } from '#/adapter/element';
import { useVbenVxeGrid, type VxeGridProps } from '#/adapter/vxe-table';
import {
  getAdminSweepLogListApi,
  SWEEP_TASK_LABELS,
  SWEEP_TASK_RETIRED,
  SWEEP_TRIGGERABLE_TASKS,
} from '#/api/bookmark-sweep-log';
import { FilterBar, FilterItem, useAutoSearch } from '#/components/filter-bar';
import BookmarkDetailDialog from '#/views/bookmark/BookmarkDetailDialog.vue';

import { formatDuration } from './duration';
import SweepRoundDetailDialog from './SweepRoundDetailDialog.vue';
import SweepTriggerDialog from './SweepTriggerDialog.vue';

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/card/index'),
    import('element-plus/es/components/card/style/css'),
  ]).then(([res]) => res.ElCard),
);

const ElSelect = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/select/index'),
    import('element-plus/es/components/select/style/css'),
  ]).then(([res]) => res.ElSelect),
);

const ElOption = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/select/index'),
    import('element-plus/es/components/select/style/css'),
  ]).then(([res]) => res.ElOption),
);

const ElSwitch = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/switch/index'),
    import('element-plus/es/components/switch/style/css'),
  ]).then(([res]) => res.ElSwitch),
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/tag/index'),
    import('element-plus/es/components/tag/style/css'),
  ]).then(([res]) => res.ElTag),
);

const ElTooltip = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/tooltip/index'),
    import('element-plus/es/components/tooltip/style/css'),
  ]).then(([res]) => res.ElTooltip),
);

/**
 * 三种巡检任务各管哪一批书签。
 *
 * 表格里只显示中文名，而三者的差别恰恰不在名字上：它们按**书签当前状态**分工，互不重叠
 * （同一条记录不会被两个任务同时 ping），而「谁有资格判死」只有存活巡检一个 —— 不写出来，
 * 「失联重试这一列失联数为什么从来不增长」这类问题只能去翻代码。
 */
const SWEEP_TASK_GUIDE: {
  desc: string;
  key: string;
  note: string;
  scope: string;
  when: string;
}[] = [
  {
    key: 'livenessCheckStaleBookmarks',
    scope: '状态为「正常」的书签',
    when: '每小时整点 · 单轮上限 200 条',
    desc: 'ping 一遍确认还活着；通了但内容已超过 30 天没更新，就顺手触发一次重新抓取刷新标题与图标。',
    note: '全系统唯一有资格把书签改判为「失联」的任务，且要连续失败达到配置次数才落定，单次失败只做退避。',
  },
  {
    key: 'retryUnreachableBookmarks',
    scope: '已判为「失联」的书签',
    when: '每小时半点 · 单轮上限 50 条',
    desc: '只要探测结果不是明确判死（含「无结论」）就触发重新抓取——抓取链路有无头浏览器与站点官方 API 两级救援，能力严格强于 ping，反爬站点在 ping 侧必然是无结论。',
    note: '只负责把书签救回来，永远不会把书签判死；失联数增长只可能来自存活巡检。',
  },
  {
    key: 'reviveArchivedBookmarks',
    scope: '已归档（失败次数耗尽）的书签',
    when: '已于 2026-08-07 下线，仅出现在历史轮次里',
    desc: '曾按天扫描归档记录做复活探测。',
    note: '归档现已是终态，复活入口改为「有用户重新添加该网址」——那是强得多的信号，也不用为再也不会回来的域名永久付探测成本。',
  },
];

const route = useRoute();

// 告警条跳过来时带 onlyBreaker=1，直接落在熔断轮次上，省掉"再点一下筛选"。
// 在 setup 里就写进初值，而不是挂载后再改：后者会让表格先按"无筛选"查一次、
// 再被自动搜索翻一次，中间那一版无关的数据还会闪一下
const searchForm = reactive({
  taskLabel: undefined as string | undefined,
  onlyBreaker: route.query.onlyBreaker === '1',
});

// 「重置」要还原成"什么都不筛"，而不是 URL 带进来的那个筛选态（见 useAutoSearch 的 initial）
const DEFAULT_FILTERS: typeof searchForm = {
  taskLabel: undefined,
  onlyBreaker: false,
};

/**
 * 候选列。
 *
 * `backlog` 与 `candidates` 的差有两个来源，含义相反：**被单轮上限截断**（要调参的信号）和
 * **非域名书签被过滤掉**（本地地址/IP，压根不该探测，完全正常）。所以告警只看 `backlog > batchSize`
 * —— 早先按 `backlog > candidates` 判，一条本地地址书签就能让橙色告警常亮。
 */
function candidateCell(row: BookmarkSweepLogVO) {
  const text =
    row.backlog === row.candidates
      ? `${row.candidates}`
      : `${row.candidates} / ${row.backlog}`;

  if (row.batchSize != null && row.backlog > row.batchSize) {
    return {
      text,
      tone: 'text-orange-500',
      tip: `到期候选共 ${row.backlog} 条，超过单轮上限 ${row.batchSize}：当前数据量下检测间隔配置已追不上，需要调大上限或拉长间隔`,
    };
  }
  if (row.backlog <= row.candidates) return { text, tone: '', tip: '' };
  return {
    text,
    tone: 'text-gray-400',
    tip:
      row.batchSize == null
        ? `到期 ${row.backlog} 条；该轮早于 2026-08-08，未记录单轮上限，无法区分这 ${row.backlog - row.candidates} 条是被上限截断还是被非域名过滤扣掉`
        : `到期 ${row.backlog} 条，其中 ${row.backlog - row.candidates} 条是本地地址/IP 等非域名书签，不做探测（单轮上限 ${row.batchSize}，未触顶）`,
  };
}

/** 短路结果里判死 / 无结论各有多少；历史行没记这个数，返回 null 表示"拆不出来" */
function shortCircuitSplit(row: BookmarkSweepLogVO) {
  if (row.shortCircuitedDead == null) return null;
  return { dead: row.shortCircuitedDead, unknown: row.shortCircuited - row.shortCircuitedDead };
}

/**
 * 失联列的说明。
 *
 * 这一列是「本轮探测 + 站点层短路复用」的合计，而熔断判据的分母只有真正探测过的那部分 ——
 * 一个域名判死会连带它名下所有页面进这个数，不说明白就会被读成"本轮死了 180 个站点"。
 */
function deadTip(row: BookmarkSweepLogVO) {
  const split = shortCircuitSplit(row);
  const reused =
    split && split.dead > 0
      ? `其中 ${split.dead} 条是站点层短路复用的上一轮站点结论、本轮并未探测，真正探测判死 ${row.deadCount - split.dead} 条；`
      : '';
  return `${reused}探测判死占实际探测 ≥90%（且实际探测 ≥20 条）时触发熔断，判定为我方出网链路故障而非站点集体下线`;
}

/** 无结论列的说明。阈值与样本量见 LivenessPolicy.breakerReason */
function unknownTip(row: BookmarkSweepLogVO) {
  const split = shortCircuitSplit(row);
  const reused =
    split && split.unknown > 0
      ? `本列含 ${split.unknown} 条站点层短路复用的结论，它们不计入熔断判据；`
      : '';
  return `「我方探不到」而非「站点失联」，不会改动书签状态。${reused}占实际探测 ≥50%（且实际探测 ≥10 条）即触发熔断`;
}

const gridOptions: VxeGridProps<BookmarkSweepLogVO> = {
  id: 'admin-bookmark-sweep-log',
  columns: [
    { type: 'seq', title: '#', width: 50 },
    {
      field: 'createTime',
      title: '轮次时间',
      width: 180,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
    { field: 'taskLabel', title: '巡检任务', width: 120, slots: { default: 'taskLabel' } },
    { field: 'breakerReason', title: '结果', minWidth: 260, slots: { default: 'result' } },
    { field: 'candidates', title: '候选', width: 90, slots: { default: 'candidates' } },
    { field: 'probed', title: '实际探测', width: 100, slots: { default: 'probed' } },
    { field: 'aliveCount', title: '存活', width: 80 },
    { field: 'deadCount', title: '失联', width: 80, slots: { default: 'dead' } },
    { field: 'unknownCount', title: '无结论', width: 90, slots: { default: 'unknown' } },
    { field: 'triggeredParse', title: '触发重抓', width: 100 },
    { field: 'deferredParse', title: '推迟', width: 90, slots: { default: 'deferred' } },
    {
      field: 'durationMs',
      title: '耗时',
      width: 100,
      formatter: ({ cellValue }) => formatDuration(cellValue),
    },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: { pageSize: 50 },
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminSweepLogListApi({
          taskLabel: searchForm.taskLabel,
          onlyBreaker: searchForm.onlyBreaker || undefined,
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

// ── 下钻：点一行看这一轮探了哪些页面 ──
// 聚合行只说得出"180 条失联"，而"是不是清一色 DEAD、集中在哪几个域名"才是区分
// 「我方出网坏了」和「真有一批站点下线」的现场，那份现场在 page_ping_log 里
const roundDetailVisible = ref(false);
const selectedRound = ref<BookmarkSweepLogVO | null>(null);

// 明细弹窗里点「详情」时由这里接手：弹窗套弹窗在 Element Plus 里遮罩会叠两层、
// ESC 也只关得掉最上面那个，所以书签详情挂在页面级，与明细弹窗平级
const bookmarkVisible = ref(false);
const bookmarkUrl = ref('');

function handleCellClick({ row }: { row: BookmarkSweepLogVO }) {
  selectedRound.value = row;
  roundDetailVisible.value = true;
}

function handleOpenBookmark(url: string) {
  bookmarkUrl.value = url;
  bookmarkVisible.value = true;
}

// 行点击必须走 gridEvents：Grid 包装组件的根节点是个 div，模板上写 @cell-click 只会
// 作为原生监听落到那个 div 上（DOM 没有 cell-click 事件），内层 VxeGrid 收不到
const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions,
  gridEvents: { cellClick: handleCellClick },
});

const { reset } = useAutoSearch(searchForm, () => gridApi.reload(), {
  initial: DEFAULT_FILTERS,
});

// ── 手动触发 ──
// 巡检是异步的：接口返回时那一轮才刚被投递，结果要等它跑完写进 sweep_log。所以触发之后
// 由这里替管理员盯着，轮到新行出现就自动刷新表格 —— 否则唯一的反馈是一句"已触发"，
// 之后只能自己反复点刷新，而一轮可能要跑好几分钟。
const triggerVisible = ref(false);
const triggerOpening = ref(false);
/** 正在等待哪个任务的新轮次落库；null = 没在等 */
const awaitingTask = ref<null | string>(null);
/**
 * 打开弹窗时各任务最新一轮的 id，用来认出"新的那一行"。
 *
 * 按任务存一份而不是只存当前选中的那个：弹窗里可以改选任务，只存一个的话，改选之后
 * 拿另一个任务的基线去比，第一次轮询就会把一条几小时前的旧轮次当成刚跑完的。
 * 取不到的任务干脆不放进来（见 onTriggered），比放一个假基线安全。
 */
const baselines = new Map<string, string | undefined>();
let pollTimer: null | ReturnType<typeof setTimeout> = null;

// 轮询节奏：一轮通常几十秒到几分钟。5s 一次足够跟手，又不至于把这个页面变成一个压测器；
// 上限按最坏耗时（200 条 × 15s 超时 ÷ 并发 8）取 10 分钟，超时就停下来交回手动刷新
const POLL_INTERVAL_MS = 5_000;
const POLL_TIMEOUT_MS = 10 * 60_000;

async function latestRoundId(taskLabel: string) {
  const res = await getAdminSweepLogListApi({
    taskLabel,
    currentPage: 1,
    pageSize: 1,
  });
  return res.records[0]?.id;
}

async function openTrigger() {
  // 基线在**打开弹窗时**取，而不是触发之后：一轮空巡检几十毫秒就跑完了，等 POST 返回再取
  // 基线会把自己那一行当成"旧的"，然后一直等一个永远不会再来的轮次。
  // 代价是打开到确认之间若正好落下一轮定时巡检，会被误认成手动那一轮 —— 定时是小时级的，
  // 而这中间只有几秒，且误判的后果仅仅是提前刷新一次表格。
  triggerOpening.value = true;
  baselines.clear();
  await Promise.all(
    SWEEP_TRIGGERABLE_TASKS.map(async (task) => {
      try {
        baselines.set(task, await latestRoundId(task));
      } catch {
        // 取不到基线就不进 map：宁可事后手动刷新，也不能拿一个不确定的值去比
      }
    }),
  );
  triggerOpening.value = false;
  triggerVisible.value = true;
}

function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
  awaitingTask.value = null;
}

function onTriggered(taskLabel: string) {
  if (!baselines.has(taskLabel)) {
    // 没有基线就没法区分"新一轮"和"上一轮"，与其瞎猜不如说清楚
    ElMessage.info('已触发，本轮完成后请手动刷新查看');
    return;
  }
  const baseline = baselines.get(taskLabel);
  awaitingTask.value = taskLabel;
  const deadline = Date.now() + POLL_TIMEOUT_MS;

  const poll = async () => {
    if (awaitingTask.value !== taskLabel) return;
    const latest = await latestRoundId(taskLabel).catch(() => undefined);
    if (latest && latest !== baseline) {
      stopPolling();
      gridApi.reload();
      ElMessage.success('本轮巡检已完成，列表已刷新');
      return;
    }
    if (Date.now() > deadline) {
      stopPolling();
      ElMessage.warning('等待超时，本轮可能仍在进行；稍后手动刷新查看');
      return;
    }
    pollTimer = setTimeout(poll, POLL_INTERVAL_MS);
  };
  pollTimer = setTimeout(poll, POLL_INTERVAL_MS);
}

// 离开页面时必须停：轮询挂在定时器上，组件销毁后它还会继续打接口，
// 并且回调里 gridApi 已经指向一个卸载了的表格
onUnmounted(stopPolling);
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span class="inline-flex items-center gap-1.5">
            活性巡检轮次
            <!--
              这块面板有三段、宽 460px，放在标题左侧的上方几乎必然溢出视口顶部；
              fallback-placements 给出下、右、左三个退路，让 popper 自己挑放得下的那个
              （Element Plus 默认只会翻到正对面那一个方向，翻过去照样放不下）。
            -->
            <ElTooltip
              effect="light"
              placement="bottom-start"
              :fallback-placements="[
                'bottom-start',
                'bottom',
                'bottom-end',
                'right-start',
                'left-start',
                'top-start',
              ]"
              :show-arrow="false"
              popper-class="max-w-none"
            >
              <CircleHelp
                class="size-4 cursor-help text-gray-400 transition-colors hover:text-gray-600"
              />
              <template #content>
                <div class="w-[460px] space-y-3 py-1 text-xs leading-relaxed">
                  <div class="text-gray-500">
                    三种任务按书签<span class="text-gray-700">当前状态</span
                    >分工，范围互不重叠，同一条记录不会被两个任务同时探测。
                  </div>
                  <div
                    v-for="task in SWEEP_TASK_GUIDE"
                    :key="task.key"
                    class="border-l-2 pl-2.5"
                    :class="
                      SWEEP_TASK_RETIRED.has(task.key)
                        ? 'border-gray-200 opacity-60'
                        : 'border-blue-400'
                    "
                  >
                    <div class="flex items-baseline gap-2">
                      <span class="text-sm font-medium">
                        {{ SWEEP_TASK_LABELS[task.key] }}
                      </span>
                      <span class="text-gray-400">{{ task.when }}</span>
                    </div>
                    <div class="mt-1 text-gray-600">
                      <span class="text-gray-400">对象：</span>{{ task.scope }}
                    </div>
                    <div class="text-gray-600">
                      <span class="text-gray-400">做什么：</span>{{ task.desc }}
                    </div>
                    <div class="text-gray-500">
                      <span class="text-gray-400">边界：</span>{{ task.note }}
                    </div>
                  </div>
                </div>
              </template>
            </ElTooltip>
          </span>
          <span class="flex items-center gap-3">
            <span class="text-xs text-gray-400">
              一轮一行，点任意一行看这一轮探了哪些页面。熔断 = 本轮整体结论被判定为不可信，没有改动任何书签
            </span>
            <!--
              放在标题栏而不是筛选栏：筛选栏那几项是"看什么"，这个按钮是"做什么"，
              混在一起会让人以为它也只是换个视角看数据 —— 而它会真的打几百个站点。
            -->
            <ElButton
              type="primary"
              size="small"
              class="shrink-0"
              :loading="triggerOpening || !!awaitingTask"
              @click="openTrigger"
            >
              {{ awaitingTask ? '巡检进行中…' : '手动触发巡检' }}
            </ElButton>
          </span>
        </div>
      </template>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="巡检任务">
          <ElSelect v-model="searchForm.taskLabel" placeholder="全部" clearable>
            <ElOption
              v-for="(label, key) in SWEEP_TASK_LABELS"
              :key="key"
              :label="SWEEP_TASK_RETIRED.has(key) ? `${label}（已下线）` : label"
              :value="key"
            />
          </ElSelect>
        </FilterItem>
        <FilterItem label="只看熔断" width="auto">
          <ElSwitch v-model="searchForm.onlyBreaker" />
        </FilterItem>
      </FilterBar>
      <Grid>
        <template #taskLabel="{ row }">
          {{ SWEEP_TASK_LABELS[row.taskLabel] ?? row.taskLabel }}
        </template>

        <template #result="{ row }">
          <ElTooltip v-if="row.breakerReason" :content="row.breakerReason" placement="top">
            <span class="inline-flex items-center gap-1.5">
              <ElTag type="danger" size="small">熔断</ElTag>
              <span class="line-clamp-1 text-xs">{{ row.breakerReason }}</span>
            </span>
          </ElTooltip>
          <ElTag v-else type="success" size="small">正常</ElTag>
        </template>

        <!-- 橙色只在 backlog 超过单轮上限时出现，判据见 candidateCell -->
        <template #candidates="{ row }">
          <ElTooltip
            v-if="candidateCell(row).tip"
            :content="candidateCell(row).tip"
            placement="top"
          >
            <span :class="candidateCell(row).tone">{{ candidateCell(row).text }}</span>
          </ElTooltip>
          <span v-else>{{ candidateCell(row).text }}</span>
        </template>

        <!-- 站点层短路：域名已判死，其下页面不再逐个探测，直接复用上一轮的站点结论 -->
        <template #probed="{ row }">
          <ElTooltip
            v-if="row.shortCircuited > 0"
            :content="`另有 ${row.shortCircuited} 条因所属域名已判定死亡被站点层短路，未实际探测；右侧失联/无结论两列含这部分复用结论，而熔断只看实际探测的 ${row.probed} 条`"
            placement="top"
          >
            <span>{{ row.probed }}<span class="text-gray-400"> +{{ row.shortCircuited }}</span></span>
          </ElTooltip>
          <span v-else>{{ row.probed }}</span>
        </template>

        <!-- 含站点层短路复用的结论，所以要说明其中真正探测出来的有多少 -->
        <template #dead="{ row }">
          <ElTooltip v-if="row.deadCount > 0" :content="deadTip(row)" placement="top">
            <span class="text-red-500">{{ row.deadCount }}</span>
          </ElTooltip>
          <span v-else>0</span>
        </template>

        <!-- 无结论 = 我方链路的问题，不会写进书签状态，但占比高就是熔断的前兆 -->
        <template #unknown="{ row }">
          <ElTooltip v-if="row.unknownCount > 0" :content="unknownTip(row)" placement="top">
            <span class="text-orange-500">{{ row.unknownCount }}</span>
          </ElTooltip>
          <span v-else>0</span>
        </template>

        <template #deferred="{ row }">
          <ElTooltip
            v-if="row.deferredParse > 0"
            content="解析队列余量不足，这些重新抓取被推迟到下一轮，避免挤占用户添加书签的队列"
            placement="top"
          >
            <span class="text-orange-500">{{ row.deferredParse }}</span>
          </ElTooltip>
          <span v-else>0</span>
        </template>
      </Grid>
    </ElCard>

    <SweepTriggerDialog v-model="triggerVisible" @triggered="onTriggered" />

    <SweepRoundDetailDialog
      v-model="roundDetailVisible"
      :round="selectedRound"
      @open-bookmark="handleOpenBookmark"
    />
    <!-- 与明细弹窗平级而非嵌套：Element Plus 里弹窗套弹窗会叠两层遮罩，ESC 也只关得掉最上面那个 -->
    <BookmarkDetailDialog v-model="bookmarkVisible" :lookup-url="bookmarkUrl" />
  </Page>
</template>
