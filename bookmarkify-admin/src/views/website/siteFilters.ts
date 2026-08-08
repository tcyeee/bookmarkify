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
  "currentPage" | "linkType" | "pageSize" | "sortAsc" | "sortField"
> & {
  /** ElDatePicker 的区间值，提交前拆成 createTimeStart / createTimeEnd */
  createRange?: [string, string];
  /** 放开默认的 linkType=DOMAIN 限制，把 localhost / 纯 IP / 未归类的站点也列出来 */
  includeNonDomain?: boolean;
};

/** 全不筛的初始值。**每次调用返回新对象** —— 共享一份会让两个视图的筛选栏互相串改。 */
export function createSiteFilters(): SiteFilters {
  return {
    keyword: "",
    nsfw: undefined,
    alive: undefined,
    verifyFlag: undefined,
    brandNameEmpty: undefined,
    minConsecutiveFail: undefined,
    createRange: undefined,
    includeNonDomain: undefined,
  };
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
    // 后台默认只看真实网站。localhost / 纯 IP / 未归类的地址一律不抓取，站点行上的品牌名、
    // 图标、活性全是空的 —— 它们既不需要人工过，也没法人工过，留在表里只是噪音。
    //
    // 但这不能是**唯一**的取值：这类书签在用户桌面上真实存在，用户报「我那条
    // 192.168.0.73:8192 坏了」时，后台必须还能把它查出来 —— 否则连"它是什么类型、
    // 因此不参与抓取"这个结论都无处得出。折叠区里给一个默认关闭的开关，
    // 平时不占版面，需要时能放开。
    linkType: filters.includeNonDomain ? undefined : "DOMAIN",
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
    // 放开范围同样要有痕迹：表里凭空多出一批没有图标没有品牌名的行，
    // 不标出来的话看起来像抓取集体失败了
    filters.includeNonDomain || undefined,
  ].filter((v) => v !== undefined && v !== null).length;
}

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
