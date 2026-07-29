<script lang="ts" setup>
import type {
  AssetDownload,
  CacheMode,
  RenderMode,
  ScrapeRequest,
  ScrapeResponse,
  WaitUntil,
} from "#/api/scrapper";

import { computed, defineAsyncComponent, reactive, ref } from "vue";

import { Page } from "@vben/common-ui";

import { ElMessage } from "element-plus";

import { scrapeDebugApi } from "#/api/scrapper";

const ElCard = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/card/index"),
    import("element-plus/es/components/card/style/css"),
  ]).then(([res]) => res.ElCard),
);
const ElButton = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/button/index"),
    import("element-plus/es/components/button/style/css"),
  ]).then(([res]) => res.ElButton),
);
const ElInput = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input/index"),
    import("element-plus/es/components/input/style/css"),
  ]).then(([res]) => res.ElInput),
);
const ElTag = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tag/index"),
    import("element-plus/es/components/tag/style/css"),
  ]).then(([res]) => res.ElTag),
);
const ElSelect = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElSelect),
);
const ElOption = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/select/index"),
    import("element-plus/es/components/select/style/css"),
  ]).then(([res]) => res.ElOption),
);
const ElSwitch = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/switch/index"),
    import("element-plus/es/components/switch/style/css"),
  ]).then(([res]) => res.ElSwitch),
);
const ElInputNumber = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/input-number/index"),
    import("element-plus/es/components/input-number/style/css"),
  ]).then(([res]) => res.ElInputNumber),
);
const ElTooltip = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tooltip/index"),
    import("element-plus/es/components/tooltip/style/css"),
  ]).then(([res]) => res.ElTooltip),
);
const ElTabs = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tabs/index"),
    import("element-plus/es/components/tabs/style/css"),
  ]).then(([res]) => res.ElTabs),
);
const ElTabPane = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/tabs/index"),
    import("element-plus/es/components/tabs/style/css"),
  ]).then(([res]) => res.ElTabPane),
);
const ElCollapse = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/collapse/index"),
    import("element-plus/es/components/collapse/style/css"),
  ]).then(([res]) => res.ElCollapse),
);
const ElCollapseItem = defineAsyncComponent(() =>
  Promise.all([
    import("element-plus/es/components/collapse/index"),
    import("element-plus/es/components/collapse/style/css"),
  ]).then(([res]) => res.ElCollapseItem),
);

// ─────────────────────────────────────────────────────────────────────────────
// 请求参数
// ─────────────────────────────────────────────────────────────────────────────

/** 与 scrapper 侧 Default 实现保持一致的初始值 */
const form = reactive({
  url: "",
  renderMode: "AUTO" as RenderMode,
  timeoutMs: null as null | number,
  waitUntil: "LOAD" as WaitUntil,
  viewportEnabled: false,
  viewportWidth: 1280,
  viewportHeight: 720,
  viewportDpr: 2,
  userAgent: "",
  locale: "",
  colorScheme: "" as "" | "DARK" | "LIGHT",
  extract: {
    meta: true,
    assets: true,
    manifest: true,
    jsonld: true,
    opengraph: true,
    twitter: true,
    feeds: false,
    alternates: false,
    text: false,
  },
  download: "PROBE" as AssetDownload,
  maxBytes: 2_097_152,
  maxCount: 20,
  screenshotEnabled: false,
  screenshotFullPage: false,
  cacheMode: "BYPASS" as CacheMode,
  robotsRespect: true,
});

/**
 * 组装请求体。
 *
 * 只发有值的字段：scrapper 侧对请求启用了 `deny_unknown_fields`，虽然多发 null 不会被
 * 拒（Option 能接受 null），但保持载荷干净更便于对照回显的 request 块排查。
 */
const builtRequest = computed<ScrapeRequest>(() => {
  const req: ScrapeRequest = {
    url: form.url.trim(),
    render: {
      mode: form.renderMode,
      waitUntil: form.waitUntil,
    },
    extract: { ...form.extract },
    assets: {
      download: form.download,
      maxBytes: form.maxBytes,
      maxCount: form.maxCount,
    },
    screenshot: {
      enabled: form.screenshotEnabled,
      fullPage: form.screenshotFullPage,
    },
    cache: { mode: form.cacheMode },
    robots: { respect: form.robotsRespect },
  };
  if (form.timeoutMs) req.render!.timeoutMs = form.timeoutMs;
  if (form.viewportEnabled) {
    req.render!.viewport = {
      width: form.viewportWidth,
      height: form.viewportHeight,
      dpr: form.viewportDpr,
    };
  }
  if (form.userAgent.trim()) req.render!.userAgent = form.userAgent.trim();
  if (form.locale.trim()) req.render!.locale = form.locale.trim();
  if (form.colorScheme) req.render!.colorScheme = form.colorScheme;
  return req;
});

const requestPreview = computed(() => JSON.stringify(builtRequest.value, null, 2));

// ─────────────────────────────────────────────────────────────────────────────
// 调用
// ─────────────────────────────────────────────────────────────────────────────

const loading = ref(false);
const result = ref<null | ScrapeResponse>(null);
const errorMsg = ref("");

async function run() {
  if (!form.url.trim()) {
    ElMessage.warning("请先输入目标 URL");
    return;
  }
  loading.value = true;
  result.value = null;
  errorMsg.value = "";
  try {
    result.value = await scrapeDebugApi(builtRequest.value);
    ElMessage.success(
      `抓取完成，用时 ${result.value.fetch.timingMs.total}ms，走的是 ${result.value.fetch.layerUsed}`,
    );
  } catch (error: any) {
    errorMsg.value = error?.message ?? String(error);
    ElMessage.error(`抓取失败：${errorMsg.value}`);
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  form.renderMode = "AUTO";
  form.timeoutMs = null;
  form.waitUntil = "LOAD";
  form.viewportEnabled = false;
  form.userAgent = "";
  form.locale = "";
  form.colorScheme = "";
  Object.assign(form.extract, {
    meta: true,
    assets: true,
    manifest: true,
    jsonld: true,
    opengraph: true,
    twitter: true,
    feeds: false,
    alternates: false,
    text: false,
  });
  form.download = "PROBE";
  form.maxBytes = 2_097_152;
  form.maxCount = 20;
  form.screenshotEnabled = false;
  form.screenshotFullPage = false;
  form.cacheMode = "BYPASS";
  form.robotsRespect = true;
}

// ─────────────────────────────────────────────────────────────────────────────
// 结果派生
// ─────────────────────────────────────────────────────────────────────────────

/** 原始 JSON：截断 dataUrl 与内联截图，否则 base64 会把面板撑爆 */
const rawJson = computed(() => {
  if (!result.value) return "";
  const clone = JSON.parse(JSON.stringify(result.value)) as ScrapeResponse;
  clone.assets?.forEach((a) => {
    if (a.dataUrl) a.dataUrl = truncate(a.dataUrl);
  });
  if (clone.screenshot?.dataUrl) {
    clone.screenshot.dataUrl = truncate(clone.screenshot.dataUrl);
  }
  return JSON.stringify(clone, null, 2);
});

function truncate(v: string) {
  return v.length > 24 ? `${v.slice(0, 24)}…(共 ${v.length} 字符)` : v;
}

/** 元数据字段逐条列出，并带上各自的出处 */
const metaRows = computed(() => {
  const m = result.value?.meta;
  if (!m) return [];
  const fields: Array<[string, string, unknown]> = [
    ["title", "标题", m.title],
    ["description", "描述", m.description],
    ["siteName", "站点名", m.siteName],
    ["shortName", "站点短名", m.shortName],
    ["canonicalUrl", "canonical", m.canonicalUrl],
    ["lang", "语言", m.lang],
    ["themeColor", "主题色", m.themeColor],
    ["author", "作者", m.author],
    ["publishedAt", "发布时间", m.publishedAt],
    ["robots", "robots", m.robots],
    ["keywords", "关键词", m.keywords?.length ? m.keywords.join(", ") : undefined],
  ];
  return fields
    .filter(([, , value]) => value !== undefined && value !== null && value !== "")
    .map(([key, label, value]) => ({
      key,
      label,
      value: String(value),
      source: m.sources?.[key],
    }));
});

/** 同一 contentHash 出现多次 = 该站没有独立 LOGO，只是把 favicon 换了个 rel 名字 */
const duplicateHashes = computed(() => {
  const counts = new Map<string, number>();
  result.value?.assets?.forEach((a) => {
    if (a.contentHash) counts.set(a.contentHash, (counts.get(a.contentHash) ?? 0) + 1);
  });
  return new Set([...counts.entries()].filter(([, n]) => n > 1).map(([h]) => h));
});

function sizeText(a: { height?: number; isVector?: boolean; width?: number }) {
  if (a.isVector) return "矢量";
  if (!a.width || !a.height) return "—";
  return `${a.width}×${a.height}`;
}

function bytesText(n?: number) {
  if (!n) return "—";
  return n < 1024 ? `${n} B` : `${(n / 1024).toFixed(1)} KB`;
}

const assetCount = computed(() => result.value?.assets?.length ?? 0);
const warnings = computed(() => result.value?.diagnostics?.warnings ?? []);

// ─────────────────────────────────────────────────────────────────────────────
// 说明文档用的静态表
// ─────────────────────────────────────────────────────────────────────────────

const RENDER_MODE_DOC: Array<[string, string]> = [
  ["AUTO", "先走 Layer 1 普通 HTTP；拿不到 <title>（常见于纯 JS 渲染页）自动回退 Layer 2 无头浏览器，并在 diagnostics.warnings 留一条记录"],
  ["HTTP", "只走 Layer 1，失败即失败，不回退。想确认某站到底需不需要无头浏览器时用它"],
  ["HEADLESS", "直接走 Layer 2。开启截图会隐含要求走这条路径"],
];

const DOWNLOAD_DOC: Array<[string, string]> = [
  ["NONE", "只报告页面声明，不发任何额外请求。尺寸只有 declared.sizes 里写的那个（可能是假的）"],
  ["PROBE", "默认值。取回图片正文算出真实尺寸 / MIME / contentHash 后丢弃，不落任何存储"],
  ["INLINE", "取回正文并编码进 dataUrl。适合小图标"],
  ["UPLOAD", "取回正文并传对象存储，返回 storageUrl。服务端未配置 OSS 时自动降级为 PROBE"],
];

const CACHE_DOC: Array<[string, string]> = [
  ["DEFAULT", "命中则用缓存，否则实时抓"],
  ["BYPASS", "无视正/负缓存强制重抓并覆盖缓存。调试时应当一直用这个，否则你可能在看一小时前的结果"],
  ["ONLY_IF_CACHED", "只用缓存，未命中直接 404，不发起任何网络请求"],
];

const ASSET_EXTRACTOR_DOC: Array<[string, string]> = [
  ["LINK_ICON", "link[rel=icon] / rel=\"shortcut icon\"，标准 favicon 声明"],
  ["LINK_MASK_ICON", "link[rel=mask-icon]，Safari 固定标签页用的矢量图"],
  ["APPLE_TOUCH_ICON", "link[rel=apple-touch-icon]，iOS 主屏图标，尺寸通常较大"],
  ["FAVICON_ICO_FALLBACK", "页面一个图标都没声明时，对 /favicon.ico 的约定式兜底探测"],
  ["MANIFEST_ICON", "Web App Manifest 的 icons[]，站点自己声明的多尺寸图标"],
  ["MS_TILE_IMAGE", "meta[name=msapplication-TileImage]，Windows 磁贴图"],
  ["JSON_LD_ORG_LOGO", "JSON-LD Organization.logo —— 唯一语义明确的品牌 LOGO"],
  ["JSON_LD_IMAGE", "JSON-LD 的 image 字段，语义较泛"],
  ["OG_IMAGE", "meta[property=og:image]，社交分享图"],
  ["TWITTER_IMAGE", "meta[name=twitter:image]，Twitter 卡片图"],
];

const META_EXTRACTOR_DOC: Array<[string, string]> = [
  ["OG", "meta[property=og:*]"],
  ["TWITTER_CARD", "meta[name=twitter:*]"],
  ["JSON_LD", "script[type=application/ld+json]"],
  ["MANIFEST", "Web App Manifest —— shortName 的唯一来源"],
  ["META_NAME", "meta[name=*]"],
  ["TITLE_TAG", "<title> 标签"],
  ["HTML_ATTR", "<html lang> 等标签属性"],
  ["LINK_TAG", "link[rel=canonical] 等 link 标签"],
];
</script>

<template>
  <Page auto-content-height>
    <div class="flex flex-col gap-4">
      <!-- ── 说明 ─────────────────────────────────────────────────────── -->
      <ElCard shadow="never">
        <template #header>
          <div class="flex items-center justify-between">
            <span class="font-medium">Scrapper 说明</span>
            <span class="text-xs text-gray-400">POST /scrape · 本页调用不写任何库</span>
          </div>
        </template>

        <div class="space-y-3 text-sm leading-relaxed">
          <div class="rounded border-l-4 border-blue-400 bg-blue-50 p-3">
            <div class="font-medium">核心约定</div>
            <p class="mt-1 text-gray-700">
              scrapper 只报告<strong>事实</strong>（页面声明了什么），不做<strong>业务解释</strong>（这些事实该当什么用）。
              所以响应里只有 <code>extractor</code>（这张图从哪个标签拿到的），<strong>没有 role</strong> ——
              「apple-touch-icon 算不算 LOGO」是书签鸭的策略，由 API 侧的映射表决定，改规则不需要动 scrapper，也不需要重抓。
            </p>
          </div>

          <div>
            <div class="mb-1 font-medium">两层抓取</div>
            <p class="text-gray-600">
              Layer 1 是普通 HTTP 请求（快、便宜）；Layer 2 是无头 Chrome（慢、贵，但能拿到 JS 渲染后的内容，且只有它能出截图）。
              手动跟随重定向，因此 <code>fetch.redirectChain</code> 能给出完整链路，每一跳都会重新过一次 SSRF 校验。
            </p>
          </div>

          <div>
            <div class="mb-1 font-medium">逐字段出处</div>
            <p class="text-gray-600">
              <code>meta.sources</code> 记录<strong>每个字段各自的</strong>来源。
              标题来自 OG、描述却回落到 <code>meta[name=description]</code> 是常态 ——
              旧版本用单一 <code>source</code> 字段把二者压扁成一个值，那个值一直在说谎，现已移除。
            </p>
          </div>

          <div>
            <div class="mb-1 font-medium">图片全量返回</div>
            <p class="text-gray-600">
              页面声明的<strong>每一张</strong>图都会独立成条，不做择优。若多条的
              <code>contentHash</code> 相同，说明该站<strong>没有独立 LOGO</strong>，只是把 favicon 换了个
              <code>rel</code> 名字 —— 下方结果表会把这种情况标出来。
            </p>
          </div>

          <ElCollapse>
            <ElCollapseItem title="参数释义：抓取模式 / 下载策略 / 缓存语义" name="params">
              <div class="space-y-3 text-xs">
                <div v-for="[title, rows] in [['render.mode', RENDER_MODE_DOC], ['assets.download', DOWNLOAD_DOC], ['cache.mode', CACHE_DOC]] as const" :key="title">
                  <div class="mb-1 font-mono font-medium">{{ title }}</div>
                  <div v-for="[k, v] in rows" :key="k" class="flex gap-2 py-0.5">
                    <span class="w-40 shrink-0 font-mono text-blue-600">{{ k }}</span>
                    <span class="flex-1 text-gray-600">{{ v }}</span>
                  </div>
                </div>
              </div>
            </ElCollapseItem>

            <ElCollapseItem title="枚举释义：图片出处 / 元数据出处" name="enums">
              <div class="space-y-3 text-xs">
                <div>
                  <div class="mb-1 font-mono font-medium">assets[].extractor</div>
                  <div v-for="[k, v] in ASSET_EXTRACTOR_DOC" :key="k" class="flex gap-2 py-0.5">
                    <span class="w-48 shrink-0 font-mono text-blue-600">{{ k }}</span>
                    <span class="flex-1 text-gray-600">{{ v }}</span>
                  </div>
                </div>
                <div>
                  <div class="mb-1 font-mono font-medium">meta.sources[].extractor</div>
                  <div v-for="[k, v] in META_EXTRACTOR_DOC" :key="k" class="flex gap-2 py-0.5">
                    <span class="w-48 shrink-0 font-mono text-blue-600">{{ k }}</span>
                    <span class="flex-1 text-gray-600">{{ v }}</span>
                  </div>
                </div>
              </div>
            </ElCollapseItem>

            <ElCollapseItem title="已知未实现 / 易踩的坑" name="gotchas">
              <ul class="ml-4 list-disc space-y-1 text-xs text-gray-600">
                <li><code>fetch.tls</code> 恒为空：reqwest 当前不透出证书细节，刻意留空而非编造</li>
                <li><code>diagnostics.robots</code> 恒为空：robots.txt 判定尚未实现，<code>robots.respect</code> 参数目前不生效</li>
                <li>无头模式拿不到重定向链：Chrome 内部跳转不经过我们，<code>redirectChain</code> 会是空数组</li>
                <li><code>PROBE</code> 会真的下载图片正文 —— contentHash 与真实像素必须读到字节才能算，它只是算完就丢</li>
                <li>请求体启用了 <code>deny_unknown_fields</code>：字段名拼错会整体 422，而不是被静默忽略</li>
              </ul>
            </ElCollapseItem>
          </ElCollapse>
        </div>
      </ElCard>

      <!-- ── 参数 ─────────────────────────────────────────────────────── -->
      <ElCard shadow="never">
        <template #header>
          <div class="flex items-center justify-between">
            <span class="font-medium">请求参数</span>
            <ElButton link size="small" @click="resetForm">恢复默认</ElButton>
          </div>
        </template>

        <div class="space-y-4">
          <div class="flex items-center gap-2">
            <ElInput
              v-model="form.url"
              placeholder="目标 URL，如 https://github.com"
              clearable
              @keyup.enter="run"
            />
            <ElButton type="primary" :loading="loading" @click="run">抓取</ElButton>
          </div>

          <div class="grid grid-cols-1 gap-x-6 gap-y-3 md:grid-cols-3">
            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">抓取模式</span>
              <ElSelect v-model="form.renderMode" size="small" class="flex-1">
                <ElOption v-for="[k] in RENDER_MODE_DOC" :key="k" :label="k" :value="k" />
              </ElSelect>
            </label>

            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">缓存</span>
              <ElSelect v-model="form.cacheMode" size="small" class="flex-1">
                <ElOption v-for="[k] in CACHE_DOC" :key="k" :label="k" :value="k" />
              </ElSelect>
            </label>

            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">图片处理</span>
              <ElSelect v-model="form.download" size="small" class="flex-1">
                <ElOption v-for="[k] in DOWNLOAD_DOC" :key="k" :label="k" :value="k" />
              </ElSelect>
            </label>

            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">就绪判定</span>
              <ElSelect v-model="form.waitUntil" size="small" class="flex-1">
                <ElOption label="LOAD" value="LOAD" />
                <ElOption label="DOM_CONTENT_LOADED" value="DOM_CONTENT_LOADED" />
                <ElOption label="NETWORK_IDLE" value="NETWORK_IDLE" />
              </ElSelect>
            </label>

            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">超时(ms)</span>
              <ElInputNumber v-model="form.timeoutMs" size="small" :min="1000" :step="1000" controls-position="right" class="flex-1" />
            </label>

            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">配色</span>
              <ElSelect v-model="form.colorScheme" size="small" clearable placeholder="默认" class="flex-1">
                <ElOption label="LIGHT" value="LIGHT" />
                <ElOption label="DARK" value="DARK" />
              </ElSelect>
            </label>

            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">最大张数</span>
              <ElInputNumber v-model="form.maxCount" size="small" :min="1" :max="100" controls-position="right" class="flex-1" />
            </label>

            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">单张上限(B)</span>
              <ElInputNumber v-model="form.maxBytes" size="small" :min="1024" :step="262144" controls-position="right" class="flex-1" />
            </label>

            <label class="flex items-center gap-2 text-sm">
              <span class="w-24 shrink-0 text-gray-500">Accept-Language</span>
              <ElInput v-model="form.locale" size="small" placeholder="zh-CN" clearable class="flex-1" />
            </label>
          </div>

          <div class="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
            <label class="flex items-center gap-2">
              <ElSwitch v-model="form.screenshotEnabled" size="small" />
              <span class="text-gray-600">截图</span>
              <ElTooltip content="开启会强制走无头浏览器" placement="top">
                <span class="cursor-help text-xs text-gray-400">(?)</span>
              </ElTooltip>
            </label>
            <label v-if="form.screenshotEnabled" class="flex items-center gap-2">
              <ElSwitch v-model="form.screenshotFullPage" size="small" />
              <span class="text-gray-600">整页截图</span>
            </label>
            <label class="flex items-center gap-2">
              <ElSwitch v-model="form.viewportEnabled" size="small" />
              <span class="text-gray-600">自定义视口</span>
            </label>
            <template v-if="form.viewportEnabled">
              <ElInputNumber v-model="form.viewportWidth" size="small" :min="320" controls-position="right" class="w-32" />
              <span class="text-gray-400">×</span>
              <ElInputNumber v-model="form.viewportHeight" size="small" :min="240" controls-position="right" class="w-32" />
              <span class="text-gray-400">@</span>
              <ElInputNumber v-model="form.viewportDpr" size="small" :min="1" :max="3" controls-position="right" class="w-24" />
            </template>
            <label class="flex items-center gap-2">
              <ElSwitch v-model="form.robotsRespect" size="small" />
              <span class="text-gray-600">遵守 robots.txt</span>
              <ElTooltip content="服务端尚未实现该判定，此开关当前不生效" placement="top">
                <span class="cursor-help text-xs text-orange-400">(未实现)</span>
              </ElTooltip>
            </label>
          </div>

          <div>
            <div class="mb-2 text-sm text-gray-500">提取模块（关掉可省下解析与网络开销）</div>
            <div class="flex flex-wrap gap-x-6 gap-y-2 text-sm">
              <label v-for="(_, key) in form.extract" :key="key" class="flex items-center gap-2">
                <ElSwitch v-model="form.extract[key]" size="small" />
                <span class="font-mono text-xs text-gray-600">{{ key }}</span>
              </label>
            </div>
          </div>

          <ElCollapse>
            <ElCollapseItem title="将要发送的请求体" name="req">
              <pre class="max-h-72 overflow-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs">{{ requestPreview }}</pre>
            </ElCollapseItem>
          </ElCollapse>
        </div>
      </ElCard>

      <!-- ── 结果 ─────────────────────────────────────────────────────── -->
      <ElCard v-if="errorMsg" shadow="never">
        <div class="text-sm text-red-500">抓取失败：{{ errorMsg }}</div>
      </ElCard>

      <ElCard v-if="result" shadow="never">
        <template #header>
          <div class="flex flex-wrap items-center gap-2">
            <span class="font-medium">抓取结果</span>
            <ElTag size="small" :type="result.fetch.layerUsed === 'HEADLESS' ? 'warning' : 'success'">
              {{ result.fetch.layerUsed }}
            </ElTag>
            <ElTag size="small" type="info">HTTP {{ result.fetch.httpStatus }}</ElTag>
            <ElTag size="small" type="info">{{ result.fetch.timingMs.total }}ms</ElTag>
            <ElTag v-if="result.fetch.fromCache" size="small">命中缓存</ElTag>
            <ElTag v-if="result.diagnostics?.antiCrawler?.detected" size="small" type="danger">
              疑似反爬页：{{ result.diagnostics.antiCrawler.signal }}
            </ElTag>
          </div>
        </template>

        <ElTabs>
          <!-- 传输层 -->
          <ElTabPane label="传输" name="fetch">
            <div class="space-y-1 text-sm">
              <div class="flex"><span class="w-28 shrink-0 text-gray-500">最终 URL</span><span class="flex-1 break-all">{{ result.fetch.finalUrl }}</span></div>
              <div class="flex"><span class="w-28 shrink-0 text-gray-500">Content-Type</span><span class="flex-1">{{ result.fetch.contentType ?? "—" }}</span></div>
              <div class="flex"><span class="w-28 shrink-0 text-gray-500">字符集</span><span class="flex-1">{{ result.fetch.charset ?? "—" }}</span></div>
              <div class="flex"><span class="w-28 shrink-0 text-gray-500">HTML 大小</span><span class="flex-1">{{ bytesText(result.fetch.byteSize) }}</span></div>
              <div class="flex">
                <span class="w-28 shrink-0 text-gray-500">重定向链</span>
                <div class="flex-1">
                  <div v-if="!result.fetch.redirectChain?.length" class="text-gray-400">无</div>
                  <div v-for="(r, i) in result.fetch.redirectChain" :key="i" class="break-all">
                    <ElTag size="small" type="info">{{ r.status }}</ElTag>
                    <span class="ml-2">{{ r.url }}</span>
                  </div>
                </div>
              </div>
            </div>
          </ElTabPane>

          <!-- 元数据 + 逐字段出处 -->
          <ElTabPane :label="`元数据 (${metaRows.length})`" name="meta">
            <div v-if="metaRows.length === 0" class="py-6 text-center text-sm text-gray-400">
              未提取到元数据（extract.meta 是否关闭了？）
            </div>
            <table v-else class="w-full text-sm">
              <thead>
                <tr class="border-b text-left text-xs text-gray-500">
                  <th class="w-24 py-1">字段</th>
                  <th class="py-1">值</th>
                  <th class="w-56 py-1">出处</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in metaRows" :key="row.key" class="border-b border-gray-50 align-top">
                  <td class="py-1.5 text-gray-500">{{ row.label }}</td>
                  <td class="py-1.5 break-all">{{ row.value }}</td>
                  <td class="py-1.5">
                    <template v-if="row.source">
                      <ElTag size="small" type="info">{{ row.source.extractor }}</ElTag>
                      <span v-if="row.source.rawKey" class="ml-1 font-mono text-xs text-gray-400">
                        {{ row.source.rawKey }}
                      </span>
                    </template>
                    <span v-else class="text-gray-300">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </ElTabPane>

          <!-- 图片资产 -->
          <ElTabPane :label="`图片 (${assetCount})`" name="assets">
            <div v-if="assetCount === 0" class="py-6 text-center text-sm text-gray-400">
              未返回图片（extract.assets 是否关闭了？）
            </div>
            <template v-else>
              <div
                v-if="duplicateHashes.size > 0"
                class="mb-3 rounded border-l-4 border-orange-400 bg-orange-50 p-2 text-xs text-gray-700"
              >
                检测到多张图片字节完全相同（下表标记 <strong>同源</strong>）——
                说明该站没有独立 LOGO，只是把 favicon 换了个 rel 名字。大图场景应走首字母色块而非拉伸。
              </div>
              <table class="w-full text-sm">
                <thead>
                  <tr class="border-b text-left text-xs text-gray-500">
                    <th class="w-16 py-1">预览</th>
                    <th class="w-48 py-1">出处</th>
                    <th class="w-24 py-1">尺寸</th>
                    <th class="w-20 py-1">大小</th>
                    <th class="py-1">地址</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(a, i) in result.assets" :key="i" class="border-b border-gray-50 align-top">
                    <td class="py-1.5">
                      <img
                        v-if="!a.error"
                        :src="a.dataUrl || a.storageUrl || a.resolvedUrl"
                        alt=""
                        class="h-8 w-8 rounded border object-contain"
                      />
                      <span v-else class="text-xs text-red-400">✕</span>
                    </td>
                    <td class="py-1.5">
                      <div class="font-mono text-xs">{{ a.extractor }}</div>
                      <div v-if="a.declared?.sizes || a.declared?.purpose" class="text-xs text-gray-400">
                        声明 {{ a.declared?.sizes }} {{ a.declared?.purpose }}
                      </div>
                      <ElTag
                        v-if="a.contentHash && duplicateHashes.has(a.contentHash)"
                        size="small"
                        type="warning"
                        class="mt-1"
                      >
                        同源
                      </ElTag>
                    </td>
                    <td class="py-1.5">{{ sizeText(a) }}</td>
                    <td class="py-1.5">{{ bytesText(a.byteSize) }}</td>
                    <td class="py-1.5">
                      <div class="break-all text-xs">{{ a.resolvedUrl }}</div>
                      <div v-if="a.error" class="text-xs text-red-500">{{ a.error }}</div>
                      <div v-if="a.contentHash" class="font-mono text-xs text-gray-400">
                        {{ a.contentHash.slice(0, 23) }}…
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </template>
          </ElTabPane>

          <!-- 截图 -->
          <ElTabPane v-if="result.screenshot" label="截图" name="shot">
            <div class="space-y-2">
              <div class="text-xs text-gray-500">
                {{ result.screenshot.width }}×{{ result.screenshot.height }} ·
                {{ result.screenshot.format }} · {{ bytesText(result.screenshot.byteSize) }}
              </div>
              <img
                :src="result.screenshot.storageUrl || result.screenshot.dataUrl"
                alt="screenshot"
                class="max-w-full rounded border"
              />
            </div>
          </ElTabPane>

          <!-- 原始块 -->
          <ElTabPane label="原始块" name="raw-blocks">
            <div class="space-y-3 text-sm">
              <div v-for="[label, value] in [['manifest', result.manifest], ['opengraph', result.opengraph], ['twitter', result.twitter], ['jsonld', result.jsonld], ['feeds', result.feeds], ['alternates', result.alternates]] as const" :key="label">
                <div class="mb-1 font-mono text-xs font-medium">{{ label }}</div>
                <pre class="max-h-48 overflow-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs">{{ value ? JSON.stringify(value, null, 2) : "—" }}</pre>
              </div>
            </div>
          </ElTabPane>

          <!-- 诊断 -->
          <ElTabPane :label="`诊断 (${warnings.length})`" name="diag">
            <div class="space-y-2 text-sm">
              <div>
                <div class="mb-1 text-xs text-gray-500">warnings</div>
                <div v-if="warnings.length === 0" class="text-gray-400">无</div>
                <ul v-else class="ml-4 list-disc space-y-1 text-xs text-orange-600">
                  <li v-for="(w, i) in warnings" :key="i">{{ w }}</li>
                </ul>
              </div>
              <div class="text-xs text-gray-500">
                antiCrawler：
                <span v-if="result.diagnostics?.antiCrawler">
                  {{ result.diagnostics.antiCrawler.detected ? "命中" : "未命中" }}
                  {{ result.diagnostics.antiCrawler.signal }}
                </span>
                <span v-else class="text-gray-400">未检测</span>
              </div>
              <div class="text-xs text-gray-500">
                robots：<span class="text-gray-400">服务端尚未实现该判定，恒为空</span>
              </div>
            </div>
          </ElTabPane>

          <!-- 原始 JSON -->
          <ElTabPane label="原始 JSON" name="raw">
            <pre class="max-h-[60vh] overflow-auto whitespace-pre-wrap break-all rounded bg-gray-50 p-2 font-mono text-xs">{{ rawJson }}</pre>
          </ElTabPane>
        </ElTabs>
      </ElCard>
    </div>
  </Page>
</template>
