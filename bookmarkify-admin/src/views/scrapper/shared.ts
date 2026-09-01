/**
 * scrapper 相关页面（调用日志 / 失败站点排行 / 巡检健康 / 测试台）共用的常量与小工具。
 *
 * 这些东西之前在 call-log 和 failed-host 里各抄了一份，`SLOW_CALL_MS` 的阈值、反爬状态码
 * 集合、按 host 的兜底图逻辑改一处漏一处 —— 收口到这里。
 */
import { SCRAPPER_ERROR_CODE_DESC } from '#/api/scrapper-call-log';

/**
 * 单次调用超过它就算慢。3s 是这条链路上有意义的那道坎：Layer 1 普通 HTTP 正常都在 1~2s 内
 * 回来，越过 3s 基本意味着对端慢、重定向链长，或者已经退到无头浏览器（生产实测无头单次 ~28s）。
 * call-log 与 failed-host 必须用同一个口径。
 */
export const SLOW_CALL_MS = 3000;

/**
 * 反爬类目标状态码：连上了但被拒，与「连不上」是完全不同的两件事。
 * 429 一并算进来 —— 频率限制同样是「站点在拒我方出口 IP」而非「站点没了」。
 */
export const ANTI_BOT_TARGET_STATUS = new Set([403, 406, 412, 429]);

export function isAntiBotStatus(status?: null | number): boolean {
  return status != null && ANTI_BOT_TARGET_STATUS.has(status);
}

/**
 * 错误码释义（认不出的码返回空串，由调用方决定只显示原始码还是走兜底）。
 * `SCRAPPER_ERROR_CODE_DESC` 里 `desc` 是长文案，`label` 是短标签。
 */
export function errorCodeDescOf(code?: null | string): string {
  return code ? (SCRAPPER_ERROR_CODE_DESC[code]?.desc ?? '') : '';
}

/**
 * 后端的 `LocalDateTime` 走 Jackson 默认的 ISO（无时区），跳转带时间窗过去时按**本地时间**
 * 原样拼，不要 `toISOString()`（那会平移成 UTC，窗口整体偏几个小时）。
 */
export function toLocalIso(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/**
 * 兜底地球图标。内联 data URI 而非引用文件，保证它自身永远不会再发一次请求。
 *
 * **不要**改回按域名拼 `https://<host>/favicon.ico`：这些页面上失效域名的密度是全后台最高的，
 * 那等于让管理员的浏览器挨个去连一批连我方抓取服务都拒掉的站点 —— 管理员公网 IP 直接暴露给
 * 第三方，控制台还会被超时和证书错误刷屏。图标为空本身就是有效信息：我方从没抓到过这个站的图标。
 */
export const FALLBACK_FAVICON = `data:image/svg+xml;utf8,${encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#9ca3af" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/></svg>`,
)}`;

/** 后端下发的 `faviconUrl`（我方 OSS 签名地址）拿不到就用本地兜底图 */
export function faviconSrc(faviconUrl?: null | string): string {
  return faviconUrl || FALLBACK_FAVICON;
}

/** `<img @error>`：已经是兜底图还报错就不再重置，避免 error 事件死循环 */
export function onFaviconError(event: Event): void {
  const img = event.target as HTMLImageElement;
  if (img.src !== FALLBACK_FAVICON) img.src = FALLBACK_FAVICON;
}
