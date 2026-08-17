import { useAccessStore } from '@vben/stores';

import type { CollectionBookmark } from './systemCollection';

export type CollectionCrawlStatus = 'CRAWLING' | 'FAILED' | 'SUCCESS';

export interface CollectionCrawlUpdate {
  jobId: string;
  rawUrl: string;
  status: CollectionCrawlStatus;
  bookmark?: CollectionBookmark;
  errorMsg?: string;
}

export interface CollectionSocketHandle {
  close: () => void;
}

/**
 * 系统书签集批量抓取的管理员 WebSocket。
 *
 * 复用后台现有 `/ws` 通道，但使用独立消息类型，避免把批量抓取进度误当成相似站点收录进度。
 */
export function createSystemCollectionSocket(
  onUpdate: (update: CollectionCrawlUpdate) => void,
): CollectionSocketHandle {
  const token = useAccessStore().accessToken ?? '';
  const wsBase = window.location.origin.replace(/^http/, 'ws');
  const url = `${wsBase}/ws?token=${encodeURIComponent(token)}&realm=ADMIN`;
  let socket: WebSocket | null = new WebSocket(url);
  let pingTimer: number | undefined;

  socket.addEventListener('open', () => {
    pingTimer = window.setInterval(() => {
      if (socket?.readyState === WebSocket.OPEN) socket.send('ping');
    }, 5000);
  });

  socket.addEventListener('message', (event) => {
    if (typeof event.data !== 'string' || event.data === 'ping' || event.data === 'pong') return;
    try {
      const message = JSON.parse(event.data) as {
        data?: CollectionCrawlUpdate;
        type?: string;
      };
      if (message.type === 'SYSTEM_COLLECTION_CRAWL_UPDATE' && message.data?.rawUrl) {
        onUpdate(message.data);
      }
    } catch {
      // 非 JSON 帧交由通道自身处理，不能阻断后续进度消息。
    }
  });

  return {
    close() {
      if (pingTimer) window.clearInterval(pingTimer);
      if (!socket) return;
      try {
        socket.close();
      } catch {
        // noop
      }
      socket = null;
    },
  };
}
