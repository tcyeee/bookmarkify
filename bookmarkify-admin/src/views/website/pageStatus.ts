import type { BookmarkParseStatus } from "#/api/bookmark";

/**
 * 页面抓取状态的展示元数据。
 *
 * `cssVar` 与 `type` 必须指向同一个颜色：健康分段条画的是 `cssVar`，旁边的状态标签用的是
 * `type`，两者一旦对不上，同一个状态在同一屏里会是两种颜色，那条 bar 就再也读不懂了。
 * 放在一起定义就是为了不让它们分头漂移。
 */
export const PAGE_STATUS_META: Record<
  BookmarkParseStatus,
  {
    cssVar: string;
    label: string;
    tip?: string;
    type: "danger" | "info" | "success" | "warning";
  }
> = {
  PENDING: { label: "等待中", type: "info", cssVar: "--el-color-info" },
  SUCCESS: { label: "成功", type: "success", cssVar: "--el-color-success" },
  UNREACHABLE: { label: "抓取失败", type: "danger", cssVar: "--el-color-danger" },
  ARCHIVED: {
    label: "已归档",
    tip: "连续失败达到阈值，已停止巡检；手动刷新/检测可恢复",
    type: "warning",
    cssVar: "--el-color-warning",
  },
};

/**
 * 分段条与图例的固定段序：**越靠右越糟**。
 *
 * 顺序是这条 bar 可读性的全部来源 —— 扫一眼右侧的颜色宽度就是问题的量，不必逐个读数字。
 * 按对象键的自然顺序渲染会让段序随 `PAGE_STATUS_META` 的书写顺序偷偷变化。
 */
export const PAGE_STATUS_ORDER: BookmarkParseStatus[] = [
  "SUCCESS",
  "PENDING",
  "UNREACHABLE",
  "ARCHIVED",
];
