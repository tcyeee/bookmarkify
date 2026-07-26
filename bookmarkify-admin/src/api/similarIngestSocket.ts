import { useAccessStore } from '@vben/stores';

/** 一键收录的逐站状态：已收录 / 已跳过(幻觉) / 本地已存在 */
export type IngestStatus = 'EXISTS' | 'INGESTED' | 'SKIPPED';

export interface IngestUpdate {
  domain: string;
  status: IngestStatus;
}

export interface IngestSocketHandle {
  close: () => void;
}

/**
 * 轻量管理端 WebSocket：仅服务「一键收录」进度推送。
 *
 * 复用后端 `/ws`（realm=ADMIN 走管理员账号体系），生命周期由调用方控制：
 * 点击收录时连接，关闭对话框时 close()，满足「关弹窗后不再接收通知」。
 */
export function createIngestSocket(
  onUpdate: (update: IngestUpdate) => void,
): IngestSocketHandle {
  const token = useAccessStore().accessToken ?? '';
  // dev: ws://localhost:5173/ws -> vite 代理到 :8001；prod: wss://admin.bookmarkify.cc/ws -> nginx 代理
  const wsBase = window.location.origin.replace(/^http/, 'ws');
  const url = `${wsBase}/ws?token=${encodeURIComponent(token)}&realm=ADMIN`;

  let socket: null | WebSocket = new WebSocket(url);
  let pingTimer: number | undefined;

  socket.addEventListener('open', () => {
    // 心跳保活（服务端不回 pong，仅靠客户端帧穿透代理空闲超时）
    pingTimer = window.setInterval(() => {
      if (socket?.readyState === WebSocket.OPEN) socket.send('ping');
    }, 5000);
  });

  socket.addEventListener('message', (event) => {
    const raw = event.data;
    if (typeof raw !== 'string' || raw === 'ping' || raw === 'pong') return;
    try {
      const msg = JSON.parse(raw) as { data: IngestUpdate; type: string };
      if (msg.type === 'SIMILAR_INGEST_UPDATE' && msg.data?.domain) {
        onUpdate(msg.data);
      }
    } catch {
      // 非 JSON 帧忽略
    }
  });

  return {
    close() {
      if (pingTimer) clearInterval(pingTimer);
      if (socket) {
        try {
          socket.close();
        } catch {
          // noop
        }
        socket = null;
      }
    },
  };
}
