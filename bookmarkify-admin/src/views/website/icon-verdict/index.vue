<script lang="ts" setup>
/**
 * 图标判定总览。
 *
 * ## 这张表回答什么
 *
 * 「改一次选图规则，效果是多少」。改造计划见仓库根 `docs/ICON-DISPLAY-TODO.md`：生产基线是
 * 153 个站点里只有 27 个（18%）真正显示了图片，其余全走首字母色块。在这个页面存在之前，
 * 那三个数只能连生产库手敲一段 SQL 拿到，于是实际上没人量 —— 规则改得对不对全凭印象。
 *
 * ## 为什么把「走色块」拆成两档
 *
 * 「够大却判色块」是**规则**的问题（选中的图尺寸达标，是出处判断否决了它，改代码就能修）；
 * 「尺寸确实不够」是**数据**的问题（站点就没提供大图，改代码修不了）。合并成一个「显示色块」
 * 会让每次规则改动都量不出效果，而量效果是这个页面存在的唯一理由。
 *
 * ## 「改进空间」是这里最该看的那个数
 *
 * 它数的是：判成了色块，可库里本来就躺着一张合格的图，只是规则没选中它。基线 31。
 * 它衡量的不是站点没提供好图，而是规则本身还差多少 —— 修完缺陷后它应当趋近 0，
 * 不降就说明改动没打中要害。
 */
import type {
  IconVerdictOverviewVO,
  IconVerdictSiteVO,
} from '#/api/icon';

import type { IconVerdict } from '#/api/enums.generated';

import { defineAsyncComponent, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { CircleHelp } from '@vben/icons';

import { useVbenVxeGrid, type VxeGridProps } from '#/adapter/vxe-table';
import {
  getAdminIconVerdictOverviewApi,
  getAdminIconVerdictSitesApi,
  ICON_VERDICT_META,
} from '#/api/icon';
import { FilterBar, FilterItem, useAutoSearch } from '#/components/filter-bar';

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

// ── 汇总 ──

const overview = ref<IconVerdictOverviewVO | null>(null);
const overviewLoading = ref(false);

async function loadOverview() {
  overviewLoading.value = true;
  try {
    overview.value = await getAdminIconVerdictOverviewApi();
  } finally {
    overviewLoading.value = false;
  }
}

onMounted(loadOverview);

function pct(count: number) {
  const total = overview.value?.siteTotal ?? 0;
  return total === 0 ? '0%' : `${Math.round((count / total) * 100)}%`;
}

/** 直方图的条长按最高的一档归一，而不是按站点总数 —— 否则 90 那一档一枝独秀，其余全是细线 */
function histogramWidth(sites: number) {
  const max = Math.max(
    1,
    ...(overview.value?.candidateHistogram ?? []).map((b) => b.sites),
  );
  return `${Math.round((sites / max) * 100)}%`;
}

const BUCKET_TONE_CLASS: Record<string, string> = {
  success: 'text-emerald-600 dark:text-emerald-400',
  warning: 'text-amber-600 dark:text-amber-400',
  info: 'text-sky-600 dark:text-sky-400',
  danger: 'text-red-600 dark:text-red-400',
};

// ── 下钻 ──

const VERDICT_OPTIONS: { label: string; value: '' | IconVerdict }[] = [
  { value: '', label: '全部' },
  ...(Object.keys(ICON_VERDICT_META) as IconVerdict[]).map((v) => ({
    value: v,
    label: ICON_VERDICT_META[v].label,
  })),
];

const DEFAULT_FILTERS = { verdict: '' as '' | IconVerdict, onlySalvageable: false };
const searchForm = reactive({ ...DEFAULT_FILTERS });

/** 兜底：图没落 OSS 时后端下发 null（刻意不回退源站直连），这里用一个不发请求的内联 SVG 占位 */
const NO_IMAGE = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#cbd5e1" stroke-width="1.4"><rect x="3" y="3" width="18" height="18" rx="3"/><path d="M3 16l5-5 4 4 3-3 6 6"/></svg>`,
)}`;

function sizeText(size?: null | number, isVector?: boolean) {
  if (isVector) return '矢量';
  return size == null || size === 0 ? '未知' : `${size}px`;
}

/**
 * 「库里最大」比「选中」还大 —— 规则没选中更好的那张，是一条可以直接动手的线索。
 * 矢量图不参与比较：它没有固有像素，和一个像素数比大小没有意义。
 */
function betterAvailable(row: IconVerdictSiteVO) {
  if (row.chosenIsVector || row.bestIsVector) return row.bestIsVector && !row.chosenIsVector;
  return (row.bestSize ?? 0) > (row.chosenSize ?? 0);
}

const gridOptions: VxeGridProps<IconVerdictSiteVO> = {
  id: 'admin-icon-verdict',
  columns: [
    { type: 'seq', title: '#', width: 50 },
    { field: 'chosenUrl', title: '选中的图', width: 90, slots: { default: 'icon' } },
    { field: 'host', title: '域名', minWidth: 200, slots: { default: 'host' } },
    { field: 'verdict', title: '判定', width: 130, slots: { default: 'verdict' } },
    {
      field: 'chosenRole',
      title: '选中那张',
      minWidth: 230,
      slots: { default: 'chosen', header: 'chosenHeader' },
    },
    {
      field: 'chosenSize',
      title: '尺寸 / 库里最大',
      width: 150,
      slots: { default: 'size', header: 'sizeHeader' },
    },
    {
      field: 'candidateCount',
      title: '候选图',
      width: 90,
      slots: { default: 'candidates', header: 'candidatesHeader' },
    },
    { field: 'salvageable', title: '有救', width: 80, slots: { default: 'salvageable' } },
  ],
  toolbarConfig: { custom: true, refresh: true },
  // 不分页：这是一张用来排查规则的表，用法是「筛出一档、逐行看图、找共性」，不是往下翻
  pagerConfig: { enabled: false },
  proxyConfig: {
    ajax: {
      query: async () => {
        const records = await getAdminIconVerdictSitesApi({
          verdict: searchForm.verdict || null,
          onlySalvageable: searchForm.onlySalvageable,
        });
        return { items: records };
      },
    },
  },
};

const [Grid, gridApi] = useVbenVxeGrid({ gridOptions });

const { reset } = useAutoSearch(
  searchForm,
  () => {
    gridApi.reload();
    // 汇总与列表读的是同一次判定，列表刷新了汇总却不刷会让两边的数字对不上
    loadOverview();
  },
  { initial: DEFAULT_FILTERS },
);

/** 点汇总卡直接筛到那一档 —— 看到「40」之后第一个动作必然是「哪 40 个」 */
function focusBucket(verdict: IconVerdict) {
  searchForm.verdict = searchForm.verdict === verdict ? '' : verdict;
  searchForm.onlySalvageable = false;
}
</script>

<template>
  <Page auto-content-height>
    <ElCard shadow="never" class="mb-4" v-loading="overviewLoading">
      <template #header>
        <div class="flex items-center justify-between">
          <span class="inline-flex items-center gap-1.5">
            图标判定总览
            <ElTooltip
              effect="light"
              placement="bottom-start"
              :fallback-placements="['bottom-start', 'bottom-end', 'right-start', 'top-start']"
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
                      「改一次选图规则，效果是多少」。这里的判定<span class="text-gray-800">现算</span
                      >，走的是线上渲染用的同一份代码（<code>AssetRolePolicy</code>），不读任何缓存列，
                      也没有第二份复刻的 SQL —— 复刻件会在规则改动后悄悄漂走，然后用一个错的数字证明改动有效。
                    </div>
                  </div>
                  <div>
                    <div class="text-sm font-medium">为什么把「走色块」拆成两档</div>
                    <div class="mt-1 text-gray-600">
                      <span class="text-gray-800">够大却判色块</span> 是规则的问题：图的尺寸达标，
                      是出处判断（<code>quality=DEGRADED</code>，意思是「这不是品牌 LOGO，只是 favicon
                      换了个 rel」）否决了它 —— 而拒绝显示图片的理由只应该是「放大会糊」。
                      <span class="text-gray-800">尺寸确实不够</span> 是数据的问题，站点就没提供大图，改代码修不了。
                      合并成一个「显示色块」会让每次规则改动都量不出效果。
                    </div>
                  </div>
                  <div>
                    <div class="text-sm font-medium">最该看的是「改进空间」</div>
                    <div class="mt-1 text-gray-600">
                      它数的是：判成了色块，可库里本来就躺着一张合格的图，只是规则没选中它。
                      基线 31。修完缺陷后它应当趋近 0；<span class="text-gray-800">它不降就说明改动没打中要害</span>。
                    </div>
                  </div>
                </div>
              </template>
            </ElTooltip>
          </span>
          <span class="text-xs text-gray-400">
            按 TILE（置顶区大图）模式判定，阈值 TILE_MIN_SIZE =
            {{ overview?.tileMinSize ?? '-' }}px。点任意一档筛到下方列表
          </span>
        </div>
      </template>

      <div v-if="overview" class="space-y-5">
        <!-- 四档 + 改进空间。改进空间单独一列并加边框：它不是第五档，是横跨前几档的一个子集 -->
        <div class="grid grid-cols-2 gap-4 md:grid-cols-5">
          <button
            v-for="bucket in overview.buckets"
            :key="bucket.verdict"
            type="button"
            class="rounded-lg border px-4 py-3 text-left transition-colors hover:bg-gray-50 dark:hover:bg-gray-800"
            :class="
              searchForm.verdict === bucket.verdict
                ? 'border-blue-400 bg-blue-50/60 dark:bg-blue-900/20'
                : 'border-gray-200 dark:border-gray-700'
            "
            @click="focusBucket(bucket.verdict)"
          >
            <ElTooltip
              effect="light"
              placement="top"
              :content="ICON_VERDICT_META[bucket.verdict].desc"
              popper-class="max-w-[420px]"
            >
              <div class="text-xs text-gray-500">
                {{ ICON_VERDICT_META[bucket.verdict].label }}
              </div>
            </ElTooltip>
            <div class="mt-1 flex items-baseline gap-2">
              <span
                class="text-2xl font-semibold"
                :class="BUCKET_TONE_CLASS[ICON_VERDICT_META[bucket.verdict].tone]"
              >
                {{ bucket.count }}
              </span>
              <span class="text-xs text-gray-400">{{ pct(bucket.count) }}</span>
            </div>
            <div class="mt-0.5 h-4 text-xs text-gray-400">
              <span v-if="bucket.salvageable > 0">其中 {{ bucket.salvageable }} 个有救</span>
            </div>
          </button>

          <div class="rounded-lg border-2 border-amber-300 px-4 py-3 dark:border-amber-700">
            <ElTooltip
              effect="light"
              placement="top"
              content="判成色块、但库里躺着一张合格候选（矢量或 TRUSTED ≥ TILE_MIN_SIZE）的站点数。它衡量的是规则本身还差多少，不是站点没提供好图"
              popper-class="max-w-[420px]"
            >
              <div class="text-xs text-gray-500">改进空间（规则可救回）</div>
            </ElTooltip>
            <div class="mt-1 flex items-baseline gap-2">
              <span class="text-2xl font-semibold text-amber-600 dark:text-amber-400">
                {{ overview.salvageable }}
              </span>
              <span class="text-xs text-gray-400">{{ pct(overview.salvageable) }}</span>
            </div>
            <div class="mt-0.5 h-4 text-xs text-gray-400">基线 31，越低越好</div>
          </div>
        </div>

        <div class="text-xs text-gray-400">
          参与判定 {{ overview.siteTotal }} 个站点。另有
          <span class="text-gray-600 dark:text-gray-300">{{ overview.siteWithoutAssets }}</span>
          个站点<span class="text-gray-600 dark:text-gray-300">一行图标资产都没有</span>，
          不参与判定 —— 那是抓取的问题（从没抓到过图），与选图规则无关，混进分母会让「规则挡掉了多少」不成立。
        </div>

        <!-- 候选图数量分布：回答「有没有的选」。基线里 59% 的站点只有一张图，选无可选 -->
        <div>
          <div class="mb-2 flex items-baseline gap-2">
            <span class="text-sm font-medium">候选图数量分布</span>
            <span class="text-xs text-gray-400">
              按 content_hash 去重后每个站点有几张真正不同的图。只有一张的那一档是「选无可选」——
              规则再怎么改也救不了，它们要靠抓取侧多拿到几张图
            </span>
          </div>
          <div class="space-y-1">
            <div
              v-for="bar in overview.candidateHistogram"
              :key="bar.candidates"
              class="flex items-center gap-3 text-xs"
            >
              <span class="w-12 shrink-0 text-right text-gray-500">
                {{ bar.candidates === 6 ? '6+' : bar.candidates }} 张
              </span>
              <div class="h-4 flex-1 rounded bg-gray-100 dark:bg-gray-800">
                <div
                  class="h-full rounded bg-blue-400/70 transition-all"
                  :style="{ width: histogramWidth(bar.sites) }"
                />
              </div>
              <span class="w-16 shrink-0 text-gray-500">{{ bar.sites }} 个</span>
            </div>
          </div>
        </div>
      </div>
    </ElCard>

    <ElCard shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>按站点下钻</span>
          <span class="text-xs text-gray-400">
            排序即优先级：有救的排最前，其次候选图多的（候选越多越可能是「选错了」而不是「没得选」）
          </span>
        </div>
      </template>

      <FilterBar class="mb-4" @reset="reset">
        <FilterItem label="判定结论" width="170px">
          <ElSelect v-model="searchForm.verdict">
            <ElOption
              v-for="opt in VERDICT_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </ElSelect>
        </FilterItem>
        <FilterItem label="只看有救的" width="60px">
          <ElSwitch v-model="searchForm.onlySalvageable" />
        </FilterItem>
      </FilterBar>

      <Grid>
        <template #icon="{ row }">
          <!-- 判成色块的行也照样显示这张图：这张表最主要的用法就是人眼核对「判色块判得对不对」，
               而那个判断没有图是做不出来的 -->
          <img
            class="size-9 rounded-md border border-gray-200 object-contain dark:border-gray-700"
            :src="row.chosenUrl || NO_IMAGE"
            alt=""
          />
        </template>

        <template #host="{ row }">
          <div class="leading-tight">
            <div class="font-medium">{{ row.host }}</div>
            <div v-if="row.brandName" class="text-xs text-gray-400">{{ row.brandName }}</div>
          </div>
        </template>

        <template #verdict="{ row }">
          <ElTooltip
            effect="light"
            placement="top"
            :content="ICON_VERDICT_META[row.verdict].desc"
            popper-class="max-w-[420px]"
          >
            <ElTag size="small" :type="ICON_VERDICT_META[row.verdict].tone">
              {{ ICON_VERDICT_META[row.verdict].label }}
            </ElTag>
          </ElTooltip>
        </template>

        <template #chosenHeader>
          <span class="inline-flex items-center gap-1">
            选中那张
            <ElTooltip
              effect="light"
              placement="top"
              content="role 是用途（我方判断），extractor 是出处（scrapper 报的事实，即这张图从哪个标签拿到的）。quality=DEGRADED 常常就是判色块的直接原因"
              popper-class="max-w-[420px]"
            >
              <CircleHelp class="size-3.5 cursor-help text-gray-400" />
            </ElTooltip>
          </span>
        </template>
        <template #chosen="{ row }">
          <div v-if="row.chosenRole" class="flex flex-wrap items-center gap-1">
            <ElTag size="small" effect="plain">{{ row.chosenRole }}</ElTag>
            <ElTag
              size="small"
              :type="row.chosenQuality === 'TRUSTED' ? 'success' : 'warning'"
              effect="plain"
            >
              {{ row.chosenQuality }}
            </ElTag>
            <span class="text-xs text-gray-400">{{ row.chosenExtractor }}</span>
          </div>
          <span v-else class="text-xs text-gray-400">—</span>
        </template>

        <template #sizeHeader>
          <span class="inline-flex items-center gap-1">
            尺寸 / 库里最大
            <ElTooltip
              effect="light"
              placement="top"
              content="两个数不一样，就说明规则没选中库里最大的那张 —— 不必再去翻资产列表，这一行就是线索"
              popper-class="max-w-[420px]"
            >
              <CircleHelp class="size-3.5 cursor-help text-gray-400" />
            </ElTooltip>
          </span>
        </template>
        <template #size="{ row }">
          <span class="text-sm">{{ sizeText(row.chosenSize, row.chosenIsVector) }}</span>
          <span class="mx-1 text-gray-300">/</span>
          <span
            class="text-sm"
            :class="betterAvailable(row) ? 'font-medium text-amber-600 dark:text-amber-400' : 'text-gray-400'"
          >
            {{ sizeText(row.bestSize, row.bestIsVector) }}
          </span>
        </template>

        <template #candidatesHeader>
          <span class="inline-flex items-center gap-1">
            候选图
            <ElTooltip
              effect="light"
              placement="top"
              content="按 content_hash 去重。为 1 表示这个站只有一张图，选无可选 —— 规则再怎么改也救不了"
              popper-class="max-w-[420px]"
            >
              <CircleHelp class="size-3.5 cursor-help text-gray-400" />
            </ElTooltip>
          </span>
        </template>
        <template #candidates="{ row }">
          <span :class="row.candidateCount <= 1 ? 'text-gray-400' : 'text-gray-700 dark:text-gray-200'">
            {{ row.candidateCount }}
          </span>
        </template>

        <template #salvageable="{ row }">
          <ElTag v-if="row.salvageable" size="small" type="warning">有救</ElTag>
          <span v-else class="text-gray-300">—</span>
        </template>
      </Grid>
    </ElCard>
  </Page>
</template>
