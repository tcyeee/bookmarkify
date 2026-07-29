/**
 * 书签小图标(iconBase64)的 data URL 组装。
 * 后端存的是裸 base64（历史数据里也可能已经是 data URL 或 http 直链），渲染前统一补上 MIME 前缀。
 */
export function buildBase64DataUrl(base64?: string): string {
  if (!base64) return "";
  const trimmed = base64.trim();
  if (trimmed.startsWith("data:") || trimmed.startsWith("http")) return trimmed;
  const mime = detectMimeFromBase64(trimmed);
  return `data:${mime};base64,${trimmed}`;
}

/** 解码前几十字节嗅探图片格式，识别不出时按 png 处理 */
export function detectMimeFromBase64(base64: string): string {
  try {
    const raw = atob(base64.slice(0, 240));
    const trimmed = raw.trimStart();
    if (trimmed.startsWith("\x89PNG")) return "image/png";
    if (trimmed.startsWith("\xFF\xD8\xFF")) return "image/jpeg";
    if (trimmed.startsWith("GIF8")) return "image/gif";
    if (
      trimmed.startsWith("<svg") ||
      trimmed.startsWith("<?xml") ||
      trimmed.toLowerCase().includes("<svg")
    )
      return "image/svg+xml";
  } catch {
    // ignore and fall back
  }
  return "image/png";
}
