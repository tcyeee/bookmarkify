import type { PageMetaVO } from "#/api/bookmark";

/**
 * `page_meta` 的展示辅助。
 *
 * 这张表此前没有任何对外出口，后台能看到"标题是什么"，却看不到"这个标题是 og:title 来的
 * 还是 <title> 兜底来的"、"这一页是 HTTP 直取还是退到了无头浏览器"、"抓的时候站点回的是
 * 200 还是 403" —— 而排查抓取质量需要的恰好是后面这些。
 */

/**
 * 实际抓取层。
 *
 * HEADLESS 不是"更好"，是"更贵"：对端 Chrome 全局串行、单次 30s 起步，一条链接退到无头
 * 说明 Layer 1 的伪装已经走尽。所以它标 warning 而不是 success。
 */
export const FETCH_LAYER_META: Record<
  string,
  { label: string; tip: string; type: "info" | "success" | "warning" }
> = {
  HEADLESS: {
    label: "无头",
    tip: "HTTP 直取被拒，退到了无头浏览器（对端 Chrome 串行，单次 30s 起步）",
    type: "warning",
  },
  HTTP: {
    label: "HTTP",
    tip: "Layer 1 直接取回，未启动浏览器",
    type: "success",
  },
};

/** HTTP 状态码的粗分色：2xx 正常、3xx/4xx 可疑、5xx 与 0 是故障 */
export function httpStatusType(
  status?: number,
): "danger" | "info" | "success" | "warning" {
  if (!status) return "info";
  if (status < 300) return "success";
  if (status < 400) return "info";
  if (status < 500) return "warning";
  return "danger";
}

/** `meta_sources` 里单个字段的出处 */
interface MetaSourceEntry {
  extractor?: string;
  rawKey?: string;
}

/**
 * 把 `meta_sources` 的 JSON 原文压成一行可读文本，形如
 * `标题←og:title｜描述←description`。
 *
 * 解析失败一律返回原文而不是空：那一列存的是抓取当时的真实字节，看不懂也比看不到强。
 */
export function formatMetaSources(json?: string): string {
  if (!json) return "";
  try {
    const parsed = JSON.parse(json) as Record<string, MetaSourceEntry>;
    const parts = Object.entries(parsed).map(([field, src]) => {
      const from = src?.rawKey || src?.extractor || "?";
      return `${META_FIELD_LABEL[field] ?? field}←${from}`;
    });
    return parts.join("｜");
  } catch {
    return json;
  }
}

const META_FIELD_LABEL: Record<string, string> = {
  description: "描述",
  lang: "语言",
  siteName: "站点名",
  siteShortName: "站点短名",
  themeColor: "主题色",
  title: "标题",
};

/**
 * 该页元数据是否值得人工看一眼：抓取层退到了无头、状态码非 2xx、或判成了反爬挑战页。
 *
 * 三者都不是"抓失败"（失败的话主表 parseStatus 就已经是 UNREACHABLE 了），而是
 * "抓回来了但内容可能不对"，这类问题只有对着 page_meta 才看得出来。
 */
export function metaNeedsAttention(meta?: PageMetaVO): boolean {
  if (!meta) return false;
  const status = meta.httpStatus;
  return (
    meta.antiCrawler ||
    meta.fetchLayer === "HEADLESS" ||
    (!!status && (status < 200 || status >= 300))
  );
}
