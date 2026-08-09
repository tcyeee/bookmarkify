<script lang="ts" setup>
/**
 * 一轮巡检的探测明细。
 *
 * 轮次表回答「巡检系统本身怎么样」，这里回答下一层：**这一轮具体探了哪些页面、各自什么结论**。
 * 最有价值的场景是熔断轮次 —— 聚合行只告诉你"180 条失联、判定不可信"，而"是不是清一色 DEAD、
 * 集中在哪几个域名"才是区分「我方出网坏了」和「真有一批站点下线」的现场。
 *
 * ## 三个必须说清楚的口径
 *
 * 1. **下表的行数等于 `probed`，不等于 `candidates`。** 被站点层短路的页面本轮压根没探过
 *    （域名已判死，不再逐页 15s 超时换同一个结论），按「一次探测一行」的语义不落日志。
 *    差额在顶部显式写出来，否则会被当成漏数据报上来。
 * 2. **查不到明细不等于没探测。** 2026-08-09 之前的轮次没有 `sweep_id` 这一列，探测日志确实
 *    存在、只是不知道属于哪一轮。这两种空必须分开讲。
 * 3. **「本轮触发的重抓」是时间窗近似。** 重抓是异步投递的，`scrapper_call_log` 里既没有轮次 ID
 *    也没有页面 ID，只能按时间圈 —— 窗口内混进其它来源的抓取是必然的，不能假装是精确关联。
 */
import type { BookmarkPingLogVO, PingOutcome } from '#/api/bookmark-ping-log';
import type { BookmarkSweepLogVO } from '#/api/bookmark-sweep-log';

import { computed, defineAsyncComponent, ref, watch } from 'vue';

import { useRouter } from 'vue-router';

import { formatDateTime } from '@vben/utils';

import {
  getAdminBookmarkPingLogListApi,
  PING_OUTCOME_META,
} from '#/api/bookmark-ping-log';
import { SWEEP_TASK_LABELS } from '#/api/bookmark-sweep-log';

const props = defineProps<{
  /** 要下钻的轮次；null 表示还没选中任何一行 */
  round?: BookmarkSweepLogVO | null;
}>();

const emit = defineEmits<{
  /** 请求打开某个页面的书签详情，由父级持有那个弹窗（避免弹窗套弹窗） */
  (e: 'openBookmark', url: string): void;
}>();

const visible = defineModel<boolean>({ default: false });

const router = useRouter();

const ElDialog = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/dialog/index'),
    import('element-plus/es/components/dialog/style/css'),
  ]).then(([res]) => res.ElDialog),
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/tag/index'),
    import('element-plus/es/components/tag/style/css'),
  ]).then(([res]) => res.ElTag),
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/button/index'),
    import('element-plus/es/components/button/style/css'),
  ]).then(([res]) => res.ElButton),
);

const ElRadioGroup = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/radio/index'),
    import('element-plus/es/components/radio/style/css'),
  ]).then(([res]) => res.ElRadioGroup),
);

const ElRadioButton = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/radio/index'),
    import('element-plus/es/components/radio/style/css'),
  ]).then(([res]) => res.ElRadioButton),
);

const ElPagination = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/pagination/index'),
    import('element-plus/es/components/pagination/style/css'),
  ]).then(([res]) => res.ElPagination),
);

const ElTooltip = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/tooltip/index'),
    import('element-plus/es/components/tooltip/style/css'),
  ]).then(([res]) => res.ElTooltip),
);

const PAGE_SIZE = 50;

/**
 * 「本轮触发的重抓」时间窗的右侧余量。
 *
 * 重抓不是巡检线程自己跑的：它 publish 一个事件，由解析池异步消费，单条最长 60s，一轮最多
 * 几十条 —— 落进 scrapper_call_log 的时刻必然晚于巡检收工。30 分钟足够覆盖排干这批队列，
 * 同时不至于把下一轮（小时级调度）的抓取整个吞进来。
 */
const PARSE_DRAIN_SLACK_MS = 30 * 60 * 1000;

const rows = ref<BookmarkPingLogVO[]>([]);
const total = ref(0);
const loading = ref(false);
const currentPage = ref(1);
const outcomeFilter = ref<'' | PingOutcome>('');

/**
 * 并发防串台：弹窗里换筛选、或者关掉再点开另一轮时，前一次请求可能后回来。
 * 用递增令牌丢弃过期结果，与书签详情弹窗里那份 pingLogToken 是同一个套路。
 */
let requestToken = 0;

const taskName = computed(() =>
  props.round
    ? (SWEEP_TASK_LABELS[props.round.taskLabel] ?? props.round.taskLabel)
    : '',
);

/** 站点层短路条数：这些页面本轮没被探测，因此不会出现在下面的列表里 */
const shortCircuited = computed(() => props.round?.shortCircuited ?? 0);

/**
 * 空列表的成因分三种，处置完全不同，不能都显示成「暂无数据」。
 * 返回 null 表示列表非空、不需要解释。
 */
const emptyReason = computed(() => {
  if (loading.value || rows.value.length > 0) return null;
  const round = props.round;
  if (!round) return null;
  if (outcomeFilter.value)
    return '本轮没有该结论的探测记录，换个结论或选「全部」看看';
  if (round.probed === 0) {
    return shortCircuited.value > 0
      ? `本轮没有实际探测：${shortCircuited.value} 条候选全部被站点层短路（所属域名已判死，直接复用上一轮站点结论），按「一次探测一行」的口径不落探测日志`
      : '本轮没有实际探测（没有到期候选，或候选全被非域名过滤扣掉）——空轮次同样要留一行，它与「巡检压根没在跑」必须可区分';
  }
  return `本轮记录了 ${round.probed} 次探测，但查不到明细：该轮早于本功能上线（2026-08-09），探测日志没有记录轮次归属。此后的轮次都能正常下钻`;
});

async function load() {
  const round = props.round;
  if (!round) return;
  const token = ++requestToken;
  loading.value = true;
  try {
    const res = await getAdminBookmarkPingLogListApi({
      sweepId: round.id,
      outcome: outcomeFilter.value || undefined,
      currentPage: currentPage.value,
      pageSize: PAGE_SIZE,
    });
    if (token !== requestToken) return;
    // 老版 API 不认识 sweepId 这个筛选项，会把全表原样返回 —— 那种情况下拿到的行属于
    // 别的轮次，展示出来比空列表更糟。按返回值自查一道，对不上就当查不到
    const mine = (res.records ?? []).filter((it) => it.sweepId === round.id);
    rows.value = mine;
    total.value = mine.length === (res.records ?? []).length ? (res.total ?? 0) : mine.length;
  } catch {
    if (token !== requestToken) return;
    rows.value = [];
    total.value = 0;
  } finally {
    if (token === requestToken) loading.value = false;
  }
}

watch(visible, (opened) => {
  if (opened) {
    currentPage.value = 1;
    outcomeFilter.value = '';
    rows.value = [];
    total.value = 0;
    load();
  } else {
    // 关闭即作废在途请求，免得下次打开时闪一下上一轮的数据
    requestToken++;
    rows.value = [];
    total.value = 0;
  }
});

watch(outcomeFilter, () => {
  currentPage.value = 1;
  load();
});

watch(currentPage, () => load());

/** 后端的 LocalDateTime 走 Jackson 默认的 ISO（无时区），按本地时间原样还原再格式化回去 */
function toLocalIso(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/**
 * 跳到调用日志页看「本轮触发的重抓后来成没成」。
 *
 * 这条边只能是时间窗：重抓异步投递，日志表里没有轮次 ID 也没有页面 ID。轮次行的
 * `createTime` 是**结束**时刻，减 `durationMs` 得起点，右界再放宽一段等解析池排干。
 */
function jumpToTriggeredParses() {
  const round = props.round;
  if (!round) return;
  const endedAt = new Date(round.createTime).getTime();
  router.push({
    path: '/scrapper/call-log',
    query: {
      from: toLocalIso(new Date(endedAt - round.durationMs)),
      to: toLocalIso(new Date(endedAt + PARSE_DRAIN_SLACK_MS)),
      note: 'sweep',
    },
  });
}

/** 单行跳转：按该页面的域名筛调用日志，看它最近被抓成什么样 */
function jumpToHostCalls(row: BookmarkPingLogVO) {
  router.push({ path: '/scrapper/call-log', query: { urlHost: row.urlHost } });
}
</script>

<template>
  <ElDialog v-model="visible" title="巡检轮次明细" width="1080px" top="6vh">
    <div v-if="round" class="space-y-3 text-sm">
      <!-- 轮次头：时间 / 任务 / 是否熔断。熔断原因整段展开，它是这个弹窗最该被读到的一句话 -->
      <div class="flex flex-wrap items-center gap-2">
        <span class="font-mono text-gray-600 dark:text-gray-300">
          {{ formatDateTime(round.createTime) }}
        </span>
        <ElTag size="small" type="info">{{ taskName }}</ElTag>
        <ElTag v-if="round.breakerReason" size="small" type="danger">熔断</ElTag>
        <ElTag v-else size="small" type="success">正常</ElTag>
      </div>
      <div
        v-if="round.breakerReason"
        class="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs leading-relaxed text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
      >
        本轮被判定为整体不可信，<b>没有改动任何书签</b>：{{ round.breakerReason }}
      </div>

      <!-- 聚合数字。放在这里是为了让下表的行数能被对上：行数 = 实际探测，不是候选 -->
      <div class="flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-gray-500">
        <span>候选 <b class="text-gray-700 dark:text-gray-200">{{ round.candidates }}</b></span>
        <span>实际探测 <b class="text-gray-700 dark:text-gray-200">{{ round.probed }}</b></span>
        <span v-if="shortCircuited > 0" class="text-gray-400">
          站点层短路 {{ shortCircuited }}
        </span>
        <span>存活 <b class="text-green-600">{{ round.aliveCount }}</b></span>
        <span>失联 <b class="text-red-500">{{ round.deadCount }}</b></span>
        <span>无结论 <b class="text-orange-500">{{ round.unknownCount }}</b></span>
        <span>触发重抓 <b class="text-gray-700 dark:text-gray-200">{{ round.triggeredParse }}</b></span>
      </div>

      <!-- 行数对不上聚合数字是必然的，不写清楚就会被当成 bug -->
      <div v-if="shortCircuited > 0" class="text-xs leading-relaxed text-gray-400">
        下表只列<b>实际探测</b>的 {{ round.probed }} 条。另外 {{ shortCircuited }}
        条因所属域名已判死被站点层短路，本轮没有真正探测（省下同样多次 15s 超时），按「一次探测一行」的口径不落日志；
        上面「失联 / 无结论」两列含这部分复用的结论，所以会大于下表能数出来的条数。
      </div>

      <div class="flex flex-wrap items-center justify-between gap-2 pt-1">
        <ElRadioGroup v-model="outcomeFilter" size="small">
          <ElRadioButton value="">全部</ElRadioButton>
          <ElRadioButton value="DEAD">失联</ElRadioButton>
          <ElRadioButton value="UNKNOWN">无结论</ElRadioButton>
          <ElRadioButton value="ALIVE">存活</ElRadioButton>
        </ElRadioGroup>
        <!-- 唯一一条通往调用日志的轮次级链接。只在真有重抓时出现，否则点过去必然是空的 -->
        <ElTooltip
          v-if="round.triggeredParse > 0"
          placement="top"
          content="重抓是异步投递的，调用日志里既没有轮次 ID 也没有页面 ID，只能按时间窗圈：轮次开始 → 结束后 30 分钟（等解析池排干）。窗口内会混进其它来源的抓取，不是精确关联"
        >
          <ElButton link size="small" type="primary" @click="jumpToTriggeredParses">
            查看本轮触发的 {{ round.triggeredParse }} 次重抓（按时间窗）
          </ElButton>
        </ElTooltip>
      </div>

      <div class="max-h-[52vh] overflow-y-auto rounded border border-gray-100 dark:border-gray-800">
        <div v-if="loading && rows.length === 0" class="px-3 py-6 text-center text-gray-400">
          加载中…
        </div>
        <div
          v-else-if="emptyReason"
          class="px-3 py-6 text-center text-xs leading-relaxed text-gray-400"
        >
          {{ emptyReason }}
        </div>
        <table v-else class="w-full text-xs">
          <thead class="sticky top-0 bg-gray-50 text-gray-500 dark:bg-gray-900">
            <tr>
              <th class="w-40 px-3 py-2 text-left font-normal">探测时间</th>
              <th class="w-20 px-2 py-2 text-left font-normal">结论</th>
              <th class="px-2 py-2 text-left font-normal">页面</th>
              <th class="w-20 px-2 py-2 text-left font-normal">重抓</th>
              <th class="w-36 px-2 py-2 text-left font-normal">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in rows"
              :key="row.id"
              class="border-t border-gray-100 dark:border-gray-800"
            >
              <td class="px-3 py-1.5 font-mono text-gray-500">
                {{ formatDateTime(row.createTime) }}
              </td>
              <td class="px-2 py-1.5">
                <ElTooltip :content="PING_OUTCOME_META[row.outcome]?.tip" placement="top">
                  <ElTag :type="PING_OUTCOME_META[row.outcome]?.type ?? 'info'" size="small">
                    {{ PING_OUTCOME_META[row.outcome]?.label ?? row.outcome }}
                  </ElTag>
                </ElTooltip>
              </td>
              <!-- 优先显示完整地址：同一域名下几十条深链，只给 host 根本分不清是哪一页。
                   页面已被删除时后端给不出 url，退回 host 并标注 -->
              <td class="max-w-0 px-2 py-1.5">
                <div class="truncate" :title="row.url ?? row.urlHost">
                  {{ row.url ?? row.urlHost }}
                </div>
                <div v-if="!row.url" class="text-[11px] text-gray-400">
                  该页面已被删除，只剩探测日志里的域名
                </div>
              </td>
              <td class="px-2 py-1.5">
                <ElTag v-if="row.triggeredParse" size="small" type="info">已触发</ElTag>
                <span v-else class="text-gray-300">-</span>
              </td>
              <td class="whitespace-nowrap px-2 py-1.5">
                <ElButton
                  v-if="row.url"
                  link
                  size="small"
                  type="primary"
                  @click="emit('openBookmark', row.url)"
                >
                  详情
                </ElButton>
                <ElButton link size="small" type="primary" @click="jumpToHostCalls(row)">
                  抓取记录
                </ElButton>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="total > PAGE_SIZE" class="flex justify-end">
        <ElPagination
          v-model:current-page="currentPage"
          :page-size="PAGE_SIZE"
          :total="total"
          background
          layout="total, prev, pager, next"
          small
        />
      </div>
    </div>
  </ElDialog>
</template>
