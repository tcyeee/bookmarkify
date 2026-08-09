/**
 * 巡检耗时的显示格式。
 *
 * 一轮最坏是「单轮上限 × 单条 15s 超时」，毫秒原样显示会出现读不动的七位数。
 * 轮次表格与手动触发确认框都要用同一套格式：确认框写「预计 3m 20s」、表格却写
 * 「200000 ms」的话，事后根本对不上自己刚才看到的那个预估。
 */
export function formatDuration(ms: number) {
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}
