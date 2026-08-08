/**
 * 分享状态 → 后台文案与标签配色。
 *
 * 列表页与详情弹窗共用一份：状态枚举加一个值时，只有一处要改。此前列表页是一条 v-if 链，
 * 漏掉了 `CANCELLED`（用户自己撤下），于是那种分享在状态列里显示成原始枚举名。
 */
type TagType = "danger" | "info" | "success" | "warning";

export const SHARE_STATUS_META: Record<string, { label: string; type: TagType }> = {
  ADMIN_TAKEDOWN: { label: "管理员下架", type: "danger" },
  CANCELLED: { label: "用户撤下", type: "info" },
  EXPIRED: { label: "到期下架", type: "info" },
  NORMAL: { label: "正常", type: "success" },
  REVIEW_REJECTED: { label: "未通过审核", type: "warning" },
};

/** 未知状态原样显示枚举名，不吞掉——后台看到生名字才知道前后端对不上 */
export function shareStatusMeta(status?: string): { label: string; type: TagType } {
  return SHARE_STATUS_META[status ?? ""] ?? { label: status || "未知", type: "info" };
}
