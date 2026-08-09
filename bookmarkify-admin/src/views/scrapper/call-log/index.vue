<script lang="ts" setup>
import type { ScrapperCallLogSearchParams, ScrapperCallLogVO } from "#/api/scrapper-call-log";

import { computed, defineAsyncComponent, reactive, ref } from "vue";

import { useRoute, useRouter } from "vue-router";

import { Page } from "@vben/common-ui";
import { formatDateTime } from "@vben/utils";

import { getAdminScrapperCallLogListApi } from "#/api/scrapper-call-log";
import { useVbenVxeGrid, type VxeGridProps } from "#/adapter/vxe-table";
import { FilterBar, FilterItem, useAutoSearch } from "#/components/filter-bar";

import BookmarkDetailDialog from "#/views/bookmark/BookmarkDetailDialog.vue";
import { isScrapableUrl, LINK_TYPE_REASON, linkTypeOfUrl } from "#/views/bookmark/linkType";
import SweepBreakerAlert from "#/views/scrapper/SweepBreakerAlert.vue";

import ScrapeResultDialog from "./ScrapeResultDialog.vue";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard)
);

const ElInput = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input/index"),
    import("element-plus/es/components/input/style/css"),
  ]).then(([res]) => res.ElInput)
);

const ElSelect = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElSelect)
);

const ElOption = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElOption)
);

const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton)
);

const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag)
);

const ElTooltip = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tooltip/index"),
    import("element-plus/es/components/tooltip/style/css"),
  ]).then(([res]) => res.ElTooltip)
);

const route = useRoute();
const router = useRouter();

// 巡检轮次页跳过来时带上筛选条件，直接落在目标数据上，省掉"再点一下筛选"。
// 在 setup 里就写进初值而不是挂载后再改：后者会让表格先按"无筛选"查一次、再被自动搜索
// 翻一次，中间那一版无关的数据还会闪一下（与巡检页的 onlyBreaker 是同一个套路）
const searchForm = reactive<Pick<ScrapperCallLogSearchParams, "urlHost" | "success">>({
  urlHost: typeof route.query.urlHost === "string" ? route.query.urlHost : "",
  success: undefined,
});

/**
 * 时间窗。**只从 URL 来，页面上没有对应的输入框** —— 它不是一个日常筛选项，而是
 * 「从某一轮巡检跳过来看这一轮触发的重抓」这一条链路的载体：那些重抓是异步投递的，
 * scrapper_call_log 里既没有轮次 ID 也没有页面 ID，除了时间没有别的东西可以对上。
 *
 * 正因为它是近似（窗口内必然混进其它来源的抓取），一旦生效就要在页面上显式挂一条提示，
 * 否则管理员会把窗口里所有的行都当成那一轮的产物。
 */
const timeWindow = computed(() => ({
  from: typeof route.query.from === "string" ? route.query.from : undefined,
  to: typeof route.query.to === "string" ? route.query.to : undefined,
}));

/** 是不是从巡检轮次跳过来的（决定提示文案说不说"这一轮"） */
const fromSweep = computed(() => route.query.note === "sweep");

/** 提示条上的可读区间，`YYYY-MM-DDTHH:mm:ss` 直接给人看太别扭 */
const timeWindowText = computed(() => {
  const { from, to } = timeWindow.value;
  if (!from && !to) return "";
  return `${from ? formatDateTime(from) : "不限"} ~ ${to ? formatDateTime(to) : "不限"}`;
});

// ── 书签解析对话框：失败行点"重试"后重新调用 scrapper 并展示其返回的全部信息 ──
const parseDialogVisible = ref(false);
const parseUrl = ref("");

function handleRetry(row: ScrapperCallLogVO) {
  // 本机/IP 地址重试多少次都是同一个结果：后端与 scrapper 都会直接拒绝(E309 /
  // FORBIDDEN_TARGET)。按钮已经禁用，这里再挡一道，防止有人从别处调进来
  if (!canRetry(row)) return;
  parseUrl.value = row.url;
  parseDialogVisible.value = true;
}

/** 这条日志能不能重试：只有域名目标可以，本机/IP 目标重试必然再失败一次 */
function canRetry(row: ScrapperCallLogVO) {
  return isScrapableUrl(row.url);
}

/** 不可重试时的悬浮说明，直接告诉管理员为什么这条没得点 */
function retryBlockedReason(row: ScrapperCallLogVO) {
  return `${LINK_TYPE_REASON[linkTypeOfUrl(row.url)]}，我方不抓取这类地址`;
}

// ── 书签详情弹窗：日志表只存了抓过哪个地址，没有书签 ID，交给弹窗按域名+路径反查 ──
const detailVisible = ref(false);
const detailUrl = ref("");

function handleCellClick({ row, column }: { row: ScrapperCallLogVO; column: any }) {
  if (column?.field === "rowActions") return;
  detailUrl.value = row.url;
  detailVisible.value = true;
}

// 兜底地球图标。内联 data URI 而非引用文件，保证它自身永远不会再发一次请求
const FALLBACK_FAVICON = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>`
)}`;

/**
 * 图标只认后端下发的 `faviconUrl`（我方 OSS 签名地址），拿不到就用本地兜底图。
 *
 * **不要**改回按域名拼 `https://${row.urlHost}/favicon.ico`。这个页面上失效域名的密度最高
 * ——域名打不开才会有失败日志——那样等于让管理员的浏览器挨个去连一批连我们的抓取服务都
 * 拒掉的站点：产品发出的请求不干净（管理员公网 IP 直接暴露给第三方），控制台还会被超时和
 * 证书错误刷屏，把真正的报错埋掉。图标为空本身就是有效信息：我方从没抓到过这个站的图标。
 */
function faviconOf(row: ScrapperCallLogVO) {
  return row.faviconUrl || FALLBACK_FAVICON;
}

function onFaviconError(event: Event) {
  const img = event.target as HTMLImageElement;
  // 已经是兜底图还报错就不再重置，避免 error 事件死循环
  if (img.src !== FALLBACK_FAVICON) img.src = FALLBACK_FAVICON;
}

/**
 * 抓取层展示映射。这一列回答的是"页面是用什么手段弄回来的"，和"来源"列（元数据从哪个标签取的）
 * 是两件事：被反爬拦下、由站点官方 API 救回来的页面，来源仍可能是 html。
 */
const LAYER_META: Record<string, { desc: string; text: string; type: "info" | "success" | "warning" }> = {
  HEADLESS: {
    desc: "Layer 2：普通 HTTP 抓不动（反爬 403/406/412），改用无头 Chrome 渲染后再解析",
    text: "Layer 2",
    type: "warning",
  },
  HTTP: {
    desc: "Layer 1：普通 HTTP 抓取，绝大多数站点走这条路",
    text: "Layer 1",
    type: "success",
  },
  SITE_API: {
    desc: "两层都被拒（拒的是机房出口 IP），元数据取自站点自己的公开 API；HTTP 状态码记的是页面那次被拒的码，不是 200",
    text: "站点 API",
    type: "info",
  },
};

/**
 * 失败行的"抓取层"回答的是另一个问题：**我们试到哪一层就放弃了**。所以释义要单独一套，
 * 照搬成功行那套（"绝大多数站点走这条路"）在一条失败记录上是答非所问。
 *
 * 这个区分有实际代价差：停在 Layer 1 说明无头浏览器还没试过，重试有意义；试到 Layer 2
 * 仍失败说明拒的是我方机房出口 IP 而不是请求长相，scrapper 会把这个 host 熔断 24 小时，
 * 同站后续 URL 直接跳过 —— 这时候点重试只是白等。
 */
const FAILED_LAYER_DESC: Record<string, string> = {
  HEADLESS:
    "已回退无头 Chrome 重试，仍然失败。多半拒的是我方机房出口 IP 而非请求长相，重试大概率还是同一个结果",
  HTTP: "停在 Layer 1 就失败了，没有回退无头：要么不是反爬类错误（超时/DNS/连不上），要么该站点近期已验证无头同样被拦、本次直接跳过",
};

function layerMetaOf(row: ScrapperCallLogVO) {
  const meta = row.layerUsed ? LAYER_META[row.layerUsed] : undefined;
  if (!meta || row.success) return meta;
  return {
    desc: FAILED_LAYER_DESC[row.layerUsed!] ?? meta.desc,
    text: meta.text,
    // 失败行一律中性色：绿/黄在这一行只会被读成"这次抓取好不好"，而那是"结果"列的事
    type: "info" as const,
  };
}

/** 来源枚举释义，鼠标悬浮在"来源"表头时展示 */
const SOURCE_LEGEND: Array<[string, string]> = [
  ["og", "命中 Open Graph 标签(og:title/og:description/og:image)，优先级最高"],
  ["twitter_card", "无 OG，命中 Twitter Card 标签(twitter:title 等)"],
  ["json_ld", "无 OG/Twitter，命中页面 JSON-LD 结构化数据(name/description/image)"],
  ["html", "以上均未命中，回退到 <title> 标签与 meta[name=description]"],
  ["headless", "普通 HTTP 抓取失败，由无头浏览器渲染后抓取，并附带页面截图"],
];

/**
 * 耗时的"网络良好/较差"分界。3s 是这条链路上有意义的那道坎：Layer 1 的普通 HTTP 抓取
 * 正常都在 1~2s 内回来，越过 3s 基本意味着对端慢、重定向链长，或者已经退到无头浏览器
 * （生产实测无头单次 ~28s）。
 */
const SLOW_CALL_MS = 3000;

function durationClassOf(row: ScrapperCallLogVO) {
  return row.durationMs < SLOW_CALL_MS
    ? "text-green-600 dark:text-green-400"
    : "text-orange-500 dark:text-orange-400";
}

/** 只有 200 算正常；其余（含 3xx/4xx/5xx）都要一眼能挑出来 */
function httpStatusClassOf(row: ScrapperCallLogVO) {
  if (row.httpStatus == null) return "text-gray-400";
  return row.httpStatus === 200
    ? "text-green-600 dark:text-green-400"
    : "text-red-600 dark:text-red-400";
}

const gridOptions: VxeGridProps<ScrapperCallLogVO> = {
  id: "admin-scrapper-call-log",
  columns: [
    { type: "seq", title: "#", width: 50 },
    { field: "urlHost", title: "域名", minWidth: 180, slots: { default: "urlHost" } },
    { field: "url", title: "请求URL", minWidth: 240, showOverflow: "tooltip" },
    { field: "success", title: "结果", width: 90, slots: { default: "success" } },
    { field: "httpStatus", title: "HTTP状态", width: 100, slots: { default: "httpStatus" } },
    {
      field: "layerUsed",
      title: "抓取层",
      width: 100,
      slots: { default: "layerUsed", header: "layerHeader" },
    },
    { field: "source", title: "来源", width: 120, slots: { header: "sourceHeader" } },
    { field: "cached", title: "缓存命中", width: 100, slots: { default: "cached" } },
    { field: "durationMs", title: "耗时(ms)", width: 100, slots: { default: "durationMs" } },
    {
      field: "createTime",
      title: "调用时间",
      width: 200,
      formatter: ({ cellValue }) => formatDateTime(cellValue),
    },
    // field 必填：点击行要弹书签详情，靠它把「操作」列排除在外
    { field: "rowActions", title: "操作", width: 90, fixed: "right", slots: { default: "action" } },
  ],
  toolbarConfig: { custom: true, refresh: true },
  pagerConfig: { pageSize: 50 },
  proxyConfig: {
    ajax: {
      query: async ({ page }) => {
        const res = await getAdminScrapperCallLogListApi({
          urlHost: searchForm.urlHost || undefined,
          success: searchForm.success,
          createTimeFrom: timeWindow.value.from,
          createTimeTo: timeWindow.value.to,
          currentPage: page.currentPage,
          pageSize: page.pageSize,
        });
        return { items: res.records, total: res.total };
      },
    },
  },
};

// 行点击必须走 gridEvents：Grid 包装组件的根节点是个 div，模板上写 @cell-click 只会
// 作为原生监听落到那个 div 上（DOM 没有 cell-click 事件），内层 VxeGrid 收不到
const [Grid, gridApi] = useVbenVxeGrid({
  gridOptions,
  gridEvents: { cellClick: handleCellClick },
});

// 「重置」要还原成"什么都不筛"，而不是 URL 带进来的那个筛选态
const { reset: resetForm } = useAutoSearch(searchForm, () => gridApi.reload(), {
  initial: { urlHost: "", success: undefined },
});

/**
 * 清掉 URL 上的时间窗（以及跳转带来的域名筛选）。
 *
 * 时间窗不在 searchForm 里，所以 useAutoSearch 的 reset 碰不到它 —— 不一起清的话，
 * 「重置」按下去域名框空了、结果却还被一个看不见的时间窗卡着，比不给重置更难排查。
 * 改 query 不会重新 setup 组件，得手动 reload 一次表格。
 */
function clearTimeWindow() {
  router.replace({ path: route.path });
  gridApi.reload();
}

function reset() {
  resetForm();
  if (timeWindowText.value || route.query.urlHost) clearTimeWindow();
}
</script>

<template>
  <Page auto-content-height>
    <!-- 巡检熔断/停摆的常驻告警。一切正常时整条不渲染，不占版面 -->
    <SweepBreakerAlert />
    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>Scrapper 调用日志</span>
        </div>
      </template>
      <!--
        时间窗生效时必须显式挂出来。它只从 URL 来、筛选栏里没有对应的输入框，不挂的话
        就是一个看不见的筛选条件：管理员会以为"这个域名最近只被抓过 3 次"，而那是窗口截出来的。
        同样要说清它是**近似**——按时间圈进来的行不都是那一轮触发的。
      -->
      <div
        v-if="timeWindowText"
        class="mb-3 flex flex-wrap items-center gap-x-2 gap-y-1 rounded border border-blue-200 bg-blue-50 px-3 py-2 text-xs leading-relaxed text-blue-700 dark:border-blue-900 dark:bg-blue-950 dark:text-blue-300"
      >
        <span>
          已按时间窗筛选：<span class="font-mono">{{ timeWindowText }}</span>
        </span>
        <span v-if="fromSweep" class="text-blue-500 dark:text-blue-400">
          来自巡检轮次的「本轮触发的重抓」。重抓是异步投递的，日志里没有轮次 ID 也没有页面
          ID，只能按时间圈 —— 窗口内会混进其它来源的抓取，不是精确关联
        </span>
        <ElButton link size="small" type="primary" @click="clearTimeWindow">
          清除
        </ElButton>
      </div>
      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="域名" width="240px">
          <ElInput v-model="searchForm.urlHost" placeholder="urlHost 模糊匹配" clearable />
        </FilterItem>
        <FilterItem label="状态" width="120px">
          <ElSelect v-model="searchForm.success" placeholder="全部" clearable>
            <ElOption label="成功" :value="true" />
            <ElOption label="失败" :value="false" />
          </ElSelect>
        </FilterItem>
      </FilterBar>
      <Grid>
        <template #urlHost="{ row }">
          <span class="inline-flex items-center justify-end gap-1.5">
            <img
              :src="faviconOf(row)"
              alt=""
              class="h-4 w-4 shrink-0 rounded-sm object-contain"
              @error="onFaviconError"
            />
            <span class="truncate">{{ row.urlHost }}</span>
          </span>
        </template>
        <template #layerHeader="{ column }">
          <ElTooltip placement="top">
            <template #content>
              <div class="max-w-md space-y-1 text-xs leading-relaxed">
                <div class="font-medium">实际抓取层(scrapper 请求发的是 AUTO，由它决定走哪层)</div>
                <div v-for="[key, meta] in Object.entries(LAYER_META)" :key="key">
                  <span class="font-mono">{{ meta.text }}</span>
                  ：{{ meta.desc }}
                </div>
                <div class="text-gray-300">
                  失败的记录这一列是"最后尝试到的层"：Layer 1 表示还没试过无头浏览器，Layer 2
                  表示试过了也没成。只有连一个字节都没发出去(地址非法、目标不是域名、并发过载)才为空
                </div>
              </div>
            </template>
            <span class="cursor-help underline decoration-dotted underline-offset-4">
              {{ column.title }}
            </span>
          </ElTooltip>
        </template>
        <template #layerUsed="{ row }">
          <ElTooltip v-if="layerMetaOf(row)" :content="layerMetaOf(row)!.desc" placement="top">
            <ElTag :type="layerMetaOf(row)!.type" size="small">
              {{ layerMetaOf(row)!.text }}
            </ElTag>
          </ElTooltip>
          <!-- 认不出的枚举值原样显示，别让新增的抓取层在后台变成一个空格 -->
          <span v-else-if="row.layerUsed">{{ row.layerUsed }}</span>
          <span v-else>-</span>
        </template>
        <template #sourceHeader="{ column }">
          <ElTooltip placement="top">
            <template #content>
              <div class="max-w-md space-y-1 text-xs leading-relaxed">
                <div class="font-medium">命中来源(scrapper 解析元数据的实际出处)</div>
                <div v-for="[key, desc] in SOURCE_LEGEND" :key="key">
                  <span class="font-mono">{{ key }}</span>
                  ：{{ desc }}
                </div>
                <div class="text-gray-300">抓取失败时该列为空</div>
              </div>
            </template>
            <span class="cursor-help underline decoration-dotted underline-offset-4">
              {{ column.title }}
            </span>
          </ElTooltip>
        </template>
        <template #action="{ row }">
          <ElTooltip v-if="!row.success && !canRetry(row)" :content="retryBlockedReason(row)" placement="top">
            <span class="cursor-not-allowed text-xs text-gray-400">不重试</span>
          </ElTooltip>
          <ElButton v-else-if="!row.success" link type="primary" size="small" @click.stop="handleRetry(row)">
            重试
          </ElButton>
          <span v-else>-</span>
        </template>
        <!-- 失败原因不再单占一列：悬浮在「失败」标签上看，长文案由 tooltip 完整展开 -->
        <template #success="{ row }">
          <ElTag v-if="row.success" type="success" size="small"> 成功 </ElTag>
          <ElTooltip v-else-if="row.errorMsg" placement="top">
            <template #content>
              <div class="max-w-md whitespace-pre-wrap text-xs leading-relaxed">{{ row.errorMsg }}</div>
            </template>
            <ElTag class="cursor-help" type="danger" size="small"> 失败 </ElTag>
          </ElTooltip>
          <!-- 失败但没留下 errorMsg：不挂空 tooltip，免得鼠标停上去弹一个空框 -->
          <ElTag v-else type="danger" size="small"> 失败 </ElTag>
        </template>
        <template #httpStatus="{ row }">
          <span :class="httpStatusClassOf(row)" class="font-mono">{{ row.httpStatus ?? "-" }}</span>
        </template>
        <template #durationMs="{ row }">
          <span :class="durationClassOf(row)" class="font-mono">{{ row.durationMs }}</span>
        </template>
        <template #cached="{ row }">
          <!-- 后端 cached 可为 null（调用没拿到响应时不写这一列），一律按「否」呈现 -->
          <ElTag :type="row.cached ? 'success' : 'info'" size="small">
            {{ row.cached ? "是" : "否" }}
          </ElTag>
        </template>
      </Grid>
    </ElCard>

    <ScrapeResultDialog v-model="parseDialogVisible" :url="parseUrl" />
    <BookmarkDetailDialog v-model="detailVisible" :lookup-url="detailUrl" />
  </Page>
</template>
