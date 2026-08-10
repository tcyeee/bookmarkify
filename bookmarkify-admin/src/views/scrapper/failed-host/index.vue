<script lang="ts" setup>
/**
 * 失败站点排行：按域名聚合一段时间窗内的抓取失败。
 *
 * 与「Scrapper 调用日志」的分工：那张表一次调用一行，翻它只看得到个例，回答不了
 * 「哪些站点在反复失败、每次烧掉多少秒」。而这两个数才是决定要不要为某类站点做特殊处理
 * （写站点官方 API 适配器、延长熔断、乃至干脆不再重试）的依据 —— 在此之前这个判断只能靠印象。
 *
 * 这个页面刻意**只呈现事实，不给「建议屏蔽」之类的结论**。同一个高失败率有完全不同的处置：
 * 反爬（403/406/412）该去写站点 API 适配器，把失败变成成功；连不上/DNS 失败是站点真的没了，
 * 交给活性巡检判失联即可；而抓取服务连不上、无头不可用压根不是站点的问题，按站点归因就是错的。
 * 区分它们的是「败因」和「目标状态」两列，不是失败率本身。
 */
import type {
  FailedHostSortField,
  ScrapperFailedHostVO,
} from '#/api/scrapper-call-log';

import { defineAsyncComponent, reactive, ref } from 'vue';

import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { CircleHelp } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { useVbenVxeGrid, type VxeGridProps } from '#/adapter/vxe-table';
import {
  getAdminFailedHostRankingApi,
  SCRAPPER_ERROR_CODE_DESC,
} from '#/api/scrapper-call-log';
import { FilterBar, FilterItem, useAutoSearch } from '#/components/filter-bar';
import BookmarkDetailDialog from '#/views/bookmark/BookmarkDetailDialog.vue';
import SweepBreakerAlert from '#/views/scrapper/SweepBreakerAlert.vue';

import { formatDuration } from '../duration';

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

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import('element-plus/es/components/button/index'),
    import('element-plus/es/components/button/style/css'),
  ]).then(([res]) => res.ElButton),
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

const router = useRouter();

const DEFAULT_FILTERS = {
  days: 30,
  minFailures: 3,
  sortField: 'failedDurationMs' as FailedHostSortField,
};

const searchForm = reactive({ ...DEFAULT_FILTERS });

const WINDOW_OPTIONS = [
  { label: '最近 24 小时', value: 1 },
  { label: '最近 7 天', value: 7 },
  { label: '最近 30 天', value: 30 },
  { label: '最近 90 天', value: 90 },
];

const MIN_FAILURE_OPTIONS = [1, 3, 5, 10, 20];

const SORT_OPTIONS: { desc: string; label: string; value: FailedHostSortField }[] =
  [
    {
      value: 'failedDurationMs',
      label: '累计浪费时间',
      desc: '默认。真正的代价是时间不是次数：一次无头失败要 30 秒，一次 DNS 失败只要几百毫秒',
    },
    {
      value: 'failedCalls',
      label: '失败次数',
      desc: '看谁来得最频繁。便宜但高频的站点会排到前面',
    },
    {
      value: 'failRate',
      label: '失败率',
      desc: '看谁最彻底。低频站点容易靠 3/3 冲到榜首，配合调高失败次数门槛使用',
    },
  ];

/** 单次调用超过它就算慢。与调用日志页同一个口径 */
const SLOW_CALL_MS = 3000;

function failRate(row: ScrapperFailedHostVO) {
  return row.totalCalls === 0 ? 0 : row.failedCalls / row.totalCalls;
}

function failRateText(row: ScrapperFailedHostVO) {
  return `${(failRate(row) * 100).toFixed(0)}%`;
}

function failRateTone(row: ScrapperFailedHostVO) {
  const rate = failRate(row);
  if (rate >= 0.99) return 'text-red-600 dark:text-red-400';
  if (rate >= 0.5) return 'text-orange-500 dark:text-orange-400';
  return 'text-gray-500';
}

/** 平均每次失败烧掉多久 —— 决定这个站点值不值得动手的单价 */
function avgFailedMs(row: ScrapperFailedHostVO) {
  return row.failedCalls === 0
    ? 0
    : Math.round(row.failedDurationMs / row.failedCalls);
}

function wasteTone(row: ScrapperFailedHostVO) {
  return avgFailedMs(row) >= SLOW_CALL_MS
    ? 'text-orange-500 dark:text-orange-400'
    : 'text-gray-600 dark:text-gray-300';
}

/**
 * 「失败涉及几个不同地址」的解读。
 *
 * 这一列把两种完全不同的形态分开了：只有 1 个地址在反复失败，是某条深链的问题（很可能就是
 * 反爬只拦内容页那一类）；接近失败次数，说明整站都抓不动。前者按域名做任何处置都会误伤。
 */
function urlsTip(row: ScrapperFailedHostVO) {
  if (row.failedUrls <= 1) {
    return '失败全部集中在同一个地址上：多半是这条深链本身的问题（比如反爬只拦内容页），按域名去处置会误伤这个站的其它页面';
  }
  if (row.failedUrls >= row.failedCalls * 0.8) {
    return `${row.failedUrls} 个不同地址各失败一次左右：整站抓不动的形态，而不是某一页的问题`;
  }
  return `${row.failedCalls} 次失败落在 ${row.failedUrls} 个不同地址上`;
}

/**
 * 窗口内成功过没有 —— 判断「这个域名是不是彻底抓不动」的关键。
 *
 * 光看失败率会误判：根路径 200、内容页 412 是很常见的形态（B 站就是这样），此时失败率很高
 * 但站点本身完全正常。真按域名屏蔽，连能抓的首页一起废掉。
 */
function successTip(row: ScrapperFailedHostVO) {
  return row.lastSuccessAt
    ? `窗口内最近一次成功：${formatDateTime(row.lastSuccessAt)}。这个域名并非抓不动 —— 常见形态是根路径正常、内容页被反爬拦下，按域名做处置会误伤`
    : '窗口内一次都没成功过。结合「败因」判断是站点真的没了（连不上/DNS），还是拒的是我方出口 IP（403/406/412）';
}

/**
 * 最近失败时间。类型上可空（VO 里 max(create_time) 是聚合结果），实际上进了榜就必然有过失败，
 * 所以只做类型收口，不额外解释"为什么没有时间"
 */
function lastFailedText(row: ScrapperFailedHostVO) {
  return row.lastFailedAt ? formatDateTime(row.lastFailedAt) : '-';
}

function errorCodeMeta(code: string) {
  return (
    SCRAPPER_ERROR_CODE_DESC[code] ?? {
      blame: 'site' as const,
      desc: '未知错误码 —— scrapper 新增了取值而后台还没跟上，去 contract.rs 查它的含义',
      label: code,
    }
  );
}

/** 我方问题的码用中性灰：它确实发生在这个域名上，但据此说"这个站点抓不动"是错的 */
function errorTagType(code: string) {
  return errorCodeMeta(code).blame === 'ours' ? 'info' : 'danger';
}

/** 表格里只铺前两个码，其余收进 tooltip —— 一个域名的败因通常就一两种，铺开会挤掉别的列 */
const VISIBLE_ERROR_CODES = 2;

function visibleErrors(row: ScrapperFailedHostVO) {
  return row.errorBreakdown.slice(0, VISIBLE_ERROR_CODES);
}

function hiddenErrorCount(row: ScrapperFailedHostVO) {
  return Math.max(0, row.errorBreakdown.length - VISIBLE_ERROR_CODES);
}

/**
 * 目标状态码的解读。这一列与「HTTP状态」不是一回事，那一列记的是 scrapper 回给我方的码
 * （FETCH_FAILED 恒 502），看不出目标站点发生了什么。
 */
function targetStatusTip(row: ScrapperFailedHostVO) {
  const status = row.lastTargetStatus;
  if (status == null) {
    return '最近一次失败没有拿到目标站点的状态码：连接就没建立起来（DNS 解析失败、连不上、超时）。这类是站点真的没了，交给活性巡检判失联即可';
  }
  if ([403, 406, 412, 429].includes(status)) {
    return `最近一次失败时目标返回 ${status}：连上了但被拒，典型的反爬。值得考虑的是写一个站点官方 API 适配器把失败变成成功，而不是不再重试`;
  }
  if (status === 404 || status === 410) {
    return `最近一次失败时目标返回 ${status}：页面不存在。这是深链失效，活性巡检会处理，与站点是否可抓无关`;
  }
  return `最近一次失败时目标站点返回 ${status}`;
}

const TARGET_STATUS_ANTI_BOT = new Set([403, 406, 412, 429]);

function targetStatusTone(row: ScrapperFailedHostVO) {
  const status = row.lastTargetStatus;
  if (status == null) return 'text-gray-400';
  return TARGET_STATUS_ANTI_BOT.has(status)
    ? 'text-orange-500 dark:text-orange-400'
    : 'text-red-600 dark:text-red-400';
}

const gridOptions: VxeGridProps<ScrapperFailedHostVO> = {
  id: 'admin-scrapper-failed-host',
  columns: [
    { type: 'seq', title: '#', width: 50 },
    { field: 'urlHost', title: '域名', minWidth: 200, slots: { default: 'urlHost' } },
    {
      field: 'failedDurationMs',
      title: '累计浪费',
      width: 130,
      slots: { default: 'waste', header: 'wasteHeader' },
    },
    { field: 'failedCalls', title: '失败/调用', width: 130, slots: { default: 'calls' } },
    { field: 'failedUrls', title: '地址数', width: 90, slots: { default: 'urls' } },
    {
      field: 'errorBreakdown',
      title: '败因',
      minWidth: 220,
      slots: { default: 'errors', header: 'errorsHeader' },
    },
    {
      field: 'lastTargetStatus',
      title: '目标状态',
      width: 100,
      slots: { default: 'targetStatus', header: 'targetStatusHeader' },
    },
    {
      field: 'lastSuccessAt',
      title: '窗口内成功过',
      width: 130,
      slots: { default: 'lastSuccess' },
    },
    {
      field: 'lastFailedAt',
      title: '最近失败',
      width: 180,
      slots: { default: 'lastFailed' },
    },
    // field 必填：点行要弹书签详情，靠它把「操作」列排除在外
    { field: 'rowActions', title: '操作', width: 100, fixed: 'right', slots: { default: 'action' } },
  ],
  toolbarConfig: { custom: true, refresh: true },
  // 排行榜不分页：翻到第 340 名对「哪几个站点最值得处理」没有意义，条数由「取前 N」控制
  pagerConfig: { enabled: false },
  proxyConfig: {
    ajax: {
      query: async () => {
        const records = await getAdminFailedHostRankingApi({
          days: searchForm.days,
          minFailures: searchForm.minFailures,
          sortField: searchForm.sortField,
        });
        return { items: records };
      },
    },
  },
};

// ── 下钻 ──
// 聚合行说得出"这个域名 30 天失败 12 次"，说不出"具体是哪几次、报了什么"。那份现场在调用日志里
const detailVisible = ref(false);
const detailUrl = ref('');

function handleCellClick({
  row,
  column,
}: {
  column: any;
  row: ScrapperFailedHostVO;
}) {
  if (column?.field === 'rowActions') return;
  // 聚合行没有书签 ID，只有最近一次失败的地址；它为空（理论上不会）就不弹，别开一个查不到东西的框
  if (!row.lastFailedUrl) return;
  detailUrl.value = row.lastFailedUrl;
  detailVisible.value = true;
}

/**
 * 跳到调用日志看这个域名的逐条记录。
 *
 * 时间窗一起带过去，否则两个页面的数字对不上 —— 排行说"30 天失败 12 次"，日志页默认不限时间
 * 会显示出更多行，而那不是同一个统计口径。
 */
function jumpToCalls(row: ScrapperFailedHostVO) {
  const from = new Date(Date.now() - searchForm.days * 86_400_000);
  router.push({
    path: '/scrapper/call-log',
    query: { urlHost: row.urlHost, from: toLocalIso(from), success: 'false' },
  });
}

/** 后端的 LocalDateTime 走 Jackson 默认的 ISO（无时区），按本地时间原样拼 */
function toLocalIso(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

// 兜底地球图标。内联 data URI 而非引用文件，保证它自身永远不会再发一次请求
const FALLBACK_FAVICON = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>`,
)}`;

/**
 * 图标只认后端下发的 `faviconUrl`（我方 OSS 签名地址），拿不到就用本地兜底图。
 *
 * **这个页面尤其不能**改回按域名拼 `https://${row.urlHost}/favicon.ico`：整张表就是一批
 * 抓不动的域名，那等于让管理员的浏览器挨个去连一批连我方抓取服务都拒掉的站点 —— 管理员的
 * 公网 IP 直接暴露给第三方，控制台还会被超时和证书错误刷屏。图标为空本身就是有效信息。
 */
function faviconOf(row: ScrapperFailedHostVO) {
  return row.faviconUrl || FALLBACK_FAVICON;
}

function onFaviconError(event: Event) {
  const img = event.target as HTMLImageElement;
  if (img.src !== FALLBACK_FAVICON) img.src = FALLBACK_FAVICON;
}

const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions,
  gridEvents: { cellClick: handleCellClick },
});

const { reset } = useAutoSearch(searchForm, () => gridApi.reload(), {
  initial: DEFAULT_FILTERS,
});
</script>

<template>
  <Page auto-content-height>
    <!-- 巡检熔断/停摆的常驻告警：熔断期间的失败是我方出网链路的问题，按站点归因会全盘读错 -->
    <SweepBreakerAlert />
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span class="inline-flex items-center gap-1.5">
            Scrapper 失败站点排行
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
                <div class="w-[480px] space-y-3 py-1 text-xs leading-relaxed">
                  <div>
                    <div class="text-sm font-medium">这张表回答什么</div>
                    <div class="mt-1 text-gray-600">
                      调用日志是一次调用一行，翻它只看得到个例。而「要不要为某类站点做特殊处理」取决于两个数：<span
                        class="text-gray-800"
                        >失败得有多频繁</span
                      >，以及<span class="text-gray-800">每次失败烧掉多少秒</span
                      >。后者才是真正的代价 —— 一次无头浏览器失败要 30 秒，一次 DNS
                      失败只要几百毫秒，同样是"失败 10 次"差了两个数量级。
                    </div>
                  </div>
                  <div>
                    <div class="text-sm font-medium">为什么不直接给"建议屏蔽"</div>
                    <div class="mt-1 text-gray-600">
                      同一个高失败率有完全不同的处置，区分它们的是「败因」和「目标状态」两列，不是失败率本身：
                    </div>
                    <ul class="mt-1 space-y-0.5 text-gray-600">
                      <li>
                        · <span class="text-gray-800">403/406/412 反爬</span> ——
                        拒的多半是我方机房出口 IP。值得做的是写一个站点官方 API
                        适配器把失败变成成功，而不是不再重试
                      </li>
                      <li>
                        · <span class="text-gray-800">目标状态为空</span> ——
                        连接压根没建立（DNS/连不上/超时），站点是真的没了，活性巡检会判失联
                      </li>
                      <li>
                        ·
                        <span class="text-gray-800"
                          >抓取服务连不上 / 无头不可用</span
                        >
                        —— 我方抖动，不是站点的问题，按站点归因就是错的（这两个码显示为灰色标签）
                      </li>
                    </ul>
                  </div>
                  <div class="text-gray-500">
                    「窗口内成功过」这一列容易被忽略但很关键：根路径 200、内容页 412
                    是很常见的形态，此时失败率很高而站点本身完全正常，按域名屏蔽会连能抓的首页一起废掉。
                  </div>
                </div>
              </template>
            </ElTooltip>
          </span>
          <span class="text-xs text-gray-400">
            默认按累计浪费时间排序。点任意一行看该域名最近一次失败的书签详情，点「查看日志」按同一时间窗下钻到逐条调用
          </span>
        </div>
      </template>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="统计窗口" width="160px">
          <ElSelect v-model="searchForm.days">
            <ElOption
              v-for="opt in WINDOW_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </FilterItem>
        <FilterItem label="失败次数≥" width="110px">
          <ElSelect v-model="searchForm.minFailures">
            <ElOption
              v-for="n in MIN_FAILURE_OPTIONS"
              :key="n"
              :label="String(n)"
              :value="n"
            />
          </ElSelect>
        </FilterItem>
        <FilterItem label="排序口径" width="180px">
          <ElSelect v-model="searchForm.sortField">
            <ElOption
              v-for="opt in SORT_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            >
              <ElTooltip :content="opt.desc" placement="right">
                <span>{{ opt.label }}</span>
              </ElTooltip>
            </ElOption>
          </ElSelect>
        </FilterItem>
      </FilterBar>
      <Grid>
        <!--
          空态必须说清是"没有问题"还是"筛没了"。默认门槛是失败 ≥3 次，而 scrapper_call_log
          的存量取决于这套日志攒了多久 —— 刚上线或刚清过表时，每个域名都够不到 3 次，
          页面会整片空白，而那看起来跟"接口坏了"一模一样。
        -->
        <template #empty>
          <div class="py-6 text-xs leading-relaxed text-gray-400">
            最近 {{ searchForm.days }} 天里没有域名的失败次数达到
            {{ searchForm.minFailures }} 次。<br />
            这可能是真的没有反复失败的站点，也可能是日志存量还不够 ——
            把「失败次数≥」调到 1、或把统计窗口拉长再看一次。
          </div>
        </template>
        <template #urlHost="{ row }">
          <span class="inline-flex items-center gap-1.5">
            <img
              :src="faviconOf(row)"
              alt=""
              class="h-4 w-4 shrink-0 rounded-sm object-contain"
              @error="onFaviconError"
            />
            <span class="truncate">{{ row.urlHost }}</span>
          </span>
        </template>

        <template #wasteHeader="{ column }">
          <ElTooltip placement="top">
            <template #content>
              <div class="max-w-md text-xs leading-relaxed">
                窗口内**失败**调用的累计耗时。成功的调用不算在里面 ——
                这一列衡量的是白白花掉的时间。<br />
                只按失败次数排会把"失败 20 次、每次 200ms"排在"失败 7 次、每次 28
                秒"前面，而真正该处理的是后者。
              </div>
            </template>
            <span class="cursor-help underline decoration-dotted underline-offset-4">
              {{ column.title }}
            </span>
          </ElTooltip>
        </template>
        <template #waste="{ row }">
          <ElTooltip
            :content="`${row.failedCalls} 次失败，平均每次 ${formatDuration(avgFailedMs(row))}；同窗口全部调用（含成功）合计 ${formatDuration(row.totalDurationMs)}`"
            placement="top"
          >
            <span :class="wasteTone(row)" class="font-mono">
              {{ formatDuration(row.failedDurationMs) }}
            </span>
          </ElTooltip>
        </template>

        <template #calls="{ row }">
          <span class="font-mono">
            <span class="text-red-600 dark:text-red-400">{{ row.failedCalls }}</span>
            <span class="text-gray-400"> / {{ row.totalCalls }}</span>
            <span :class="failRateTone(row)" class="ml-1.5">{{ failRateText(row) }}</span>
          </span>
        </template>

        <template #urls="{ row }">
          <ElTooltip :content="urlsTip(row)" placement="top">
            <span class="cursor-help font-mono">{{ row.failedUrls }}</span>
          </ElTooltip>
        </template>

        <template #errorsHeader="{ column }">
          <ElTooltip placement="top">
            <template #content>
              <div class="max-w-md space-y-1 text-xs leading-relaxed">
                <div class="font-medium">窗口内该域名各错误码出现的次数</div>
                <div>
                  <span class="text-red-300">红色</span>=关于目标站点的结论；
                  <span class="text-gray-300">灰色</span
                  >=我方自己的状态（抓取服务连不上、无头不可用、契约不匹配等）。
                  灰色的行不该按站点去归因，它换个时间重试就好了。
                </div>
                <div class="text-gray-300">
                  UNKNOWN 表示这条记录早于 2026-08-10 的错误码迁移，当时没有记录败因
                </div>
              </div>
            </template>
            <span class="cursor-help underline decoration-dotted underline-offset-4">
              {{ column.title }}
            </span>
          </ElTooltip>
        </template>
        <template #errors="{ row }">
          <span v-if="row.errorBreakdown.length === 0" class="text-gray-400">-</span>
          <span v-else class="inline-flex flex-wrap items-center gap-1">
            <ElTooltip
              v-for="item in visibleErrors(row)"
              :key="item.errorCode"
              :content="errorCodeMeta(item.errorCode).desc"
              placement="top"
            >
              <ElTag
                class="cursor-help"
                :type="errorTagType(item.errorCode)"
                size="small"
              >
                {{ errorCodeMeta(item.errorCode).label }} × {{ item.count }}
              </ElTag>
            </ElTooltip>
            <ElTooltip v-if="hiddenErrorCount(row) > 0" placement="top">
              <template #content>
                <div class="max-w-xs space-y-0.5 text-xs leading-relaxed">
                  <div v-for="item in row.errorBreakdown" :key="item.errorCode">
                    {{ errorCodeMeta(item.errorCode).label }} × {{ item.count }}
                  </div>
                </div>
              </template>
              <span class="cursor-help text-xs text-gray-400">
                +{{ hiddenErrorCount(row) }}
              </span>
            </ElTooltip>
          </span>
        </template>

        <template #targetStatusHeader="{ column }">
          <ElTooltip placement="top">
            <template #content>
              <div class="max-w-md text-xs leading-relaxed">
                最近一次失败时<span class="font-medium">目标站点</span
                >返回的状态码。与调用日志里的「HTTP状态」不是一回事 ——
                那一列记的是 scrapper 回给我方的码（取回失败恒为 502、超时恒为
                504），看不出目标站点发生了什么。<br />
                为空表示连接压根没建立（DNS/连不上/超时）。这一列是「反爬」与「站点真的没了」唯一分得开的地方。
              </div>
            </template>
            <span class="cursor-help underline decoration-dotted underline-offset-4">
              {{ column.title }}
            </span>
          </ElTooltip>
        </template>
        <template #targetStatus="{ row }">
          <ElTooltip :content="targetStatusTip(row)" placement="top">
            <span :class="targetStatusTone(row)" class="cursor-help font-mono">
              {{ row.lastTargetStatus ?? '无' }}
            </span>
          </ElTooltip>
        </template>

        <template #lastSuccess="{ row }">
          <ElTooltip :content="successTip(row)" placement="top">
            <ElTag
              class="cursor-help"
              :type="row.lastSuccessAt ? 'success' : 'danger'"
              size="small"
            >
              {{ row.lastSuccessAt ? '成功过' : '从未成功' }}
            </ElTag>
          </ElTooltip>
        </template>

        <template #lastFailed="{ row }">
          <ElTooltip v-if="row.lastErrorMsg" placement="top">
            <template #content>
              <div class="max-w-md whitespace-pre-wrap text-xs leading-relaxed">
                <div class="mb-1 font-mono text-gray-300">{{ row.lastFailedUrl }}</div>
                {{ row.lastErrorMsg }}
              </div>
            </template>
            <span class="cursor-help">{{ lastFailedText(row) }}</span>
          </ElTooltip>
          <span v-else>{{ lastFailedText(row) }}</span>
        </template>

        <template #action="{ row }">
          <ElButton link type="primary" size="small" @click.stop="jumpToCalls(row)">
            查看日志
          </ElButton>
        </template>
      </Grid>
    </ElCard>

    <BookmarkDetailDialog v-model="detailVisible" :lookup-url="detailUrl" />
  </Page>
</template>
