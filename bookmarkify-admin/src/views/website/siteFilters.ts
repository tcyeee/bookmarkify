import type { SiteSearchParams } from "#/api/site";

/**
 * 站点筛选表单的模型。
 *
 * 与 {@link SiteSearchParams} 差在 `createRange`：ElDatePicker 的区间是一个二元组，
 * 而接口收的是拆开的起止两个字段。这层转换以前在「站点管理」和「站点与页面」里各写了一份，
 * 改一个筛选项要记得改两处 —— 现在只在 {@link toSiteSearchParams} 里做一次。
 */
export type SiteFilters = Omit<
  SiteSearchParams,
  "currentPage" | "pageSize" | "sortAsc" | "sortField"
> & {
  /** ElDatePicker 的区间值，提交前拆成 createTimeStart / createTimeEnd */
  createRange?: [string, string];
};

/** 全不筛的初始值。**每次调用返回新对象** —— 共享一份会让两个视图的筛选栏互相串改。 */
export function createSiteFilters(): SiteFilters {
  return {
    keyword: "",
    linkType: undefined,
    nsfw: undefined,
    alive: undefined,
    verifyFlag: undefined,
    brandNameEmpty: undefined,
    minConsecutiveFail: undefined,
    createRange: undefined,
  };
}

/** 就地重置（保持同一个 reactive 对象的引用，v-model 绑定才不会断） */
export function resetSiteFilters(filters: SiteFilters) {
  Object.assign(filters, createSiteFilters());
}

/**
 * 表单模型 → 接口入参。
 *
 * 空串一律转 `undefined`：后端把 `null` 当「不筛」，而 `keyword: ""` 会被当成一个真实的
 * 空关键字参与 like 匹配。
 */
export function toSiteSearchParams(filters: SiteFilters): SiteSearchParams {
  return {
    keyword: filters.keyword || undefined,
    linkType: filters.linkType,
    nsfw: filters.nsfw,
    alive: filters.alive,
    verifyFlag: filters.verifyFlag,
    brandNameEmpty: filters.brandNameEmpty,
    minConsecutiveFail: filters.minConsecutiveFail ?? undefined,
    createTimeStart: filters.createRange?.[0],
    createTimeEnd: filters.createRange?.[1],
  };
}

/**
 * 折叠区里有几个筛选项正在生效。
 *
 * 折叠起来的筛选项如果没有任何痕迹，用户会对着一个被过滤过的列表找不到原因 ——
 * 这个数字挂在「更多筛选」按钮上当角标。
 */
export function countAdvancedFilters(filters: SiteFilters): number {
  return [
    filters.nsfw,
    filters.verifyFlag,
    filters.brandNameEmpty,
    filters.minConsecutiveFail,
    filters.createRange,
  ].filter((v) => v !== undefined && v !== null).length;
}

/** 链接类型的展示元数据。`as const` 保住键的字面量类型，别让它退化成 string */
export const LINK_TYPE_META = {
  DOMAIN: { label: "域名", tip: "正常网站地址，会抓取标题/图标", type: "success" },
  LOCAL: { label: "本地", tip: "localhost / 127.0.0.1 / ::1，不会被抓取", type: "info" },
  IP: { label: "IP", tip: "纯 IP 地址，不会被抓取", type: "warning" },
  OTHER: { label: "其他", tip: "未归类", type: "info" },
} as const satisfies Record<
  NonNullable<SiteSearchParams["linkType"]>,
  { label: string; tip: string; type: "danger" | "info" | "success" | "warning" }
>;

/** 站点级人工锁定字段的中文名 */
export const SITE_LOCKED_FIELD_LABEL = {
  BRAND_NAME: "全名已锁定",
  SHORT_NAME: "短名已锁定",
} as const;

/** 三态筛选统一用这套选项：不选=不筛，与后端 null=不筛一致，别拿哨兵值代替 */
export const BOOL_OPTIONS = [
  { label: "是", value: true },
  { label: "否", value: false },
];
