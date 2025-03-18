import { defineStore } from 'pinia'
import type { SocketMessage } from './types';

export const useWebSocketStore = defineStore('socket', {
  persist: false,
  state: () => ({
    socket: undefined as WebSocket | undefined,
    actions: new Map() as Map<SocketTypes, Function>,
    pingInterval: undefined as number | undefined,
    reconnectTimeout: undefined as number | undefined,
    isConnected: false,   // WebSocket 是否在线
    reconnectAttempts: 0, // 重连次数
    pingIntervalValue: 5  // 心跳间隔(秒)
  }),
  actions: {
    connect(token: string) {
      if (this.socket) return;

      const userStore = useUserStore()
      this.socket = new WebSocket(`${useRuntimeConfig().public.wsBase}/ws?token=${userStore.auth.token}`)

      this.socket.onerror = (error) => {
        console.error("[WebSocket] 发生错误:", error);
        this.isConnected = false;
      };

      this.socket.onclose = () => {
        console.log("[WebSocket] 连接关闭");
        this.socket = undefined;
        this.isConnected = false;
        this.stopHeartbeat(); // 停止心跳
        this.reconnect(); // 触发自动重连
      };

      this.socket.onopen = () => {
        console.log('[WebSocket] 已连接..');
        this.isConnected = true;
        this.reconnectAttempts = 0; // 重置重连次数
        this.startHeartbeat(); // 开启心跳检测
      };

      this.socket.onmessage = (event) => {
        const message = JSON.parse(event.data) as SocketMessage;
        console.log("[WebSocket] 收到消息:", message.type);
        if (message.type === SocketTypes.BOOKMARK_UPDATE_ONE) {
          const bookmarkStore = useBookmarkStore()
          bookmarkStore.updateOne(message.data);
        }
      }
    },

    startHeartbeat() {
      this.stopHeartbeat(); // 先清除之前的心跳
      this.pingInterval = window.setInterval(() => {
        if (this.socket?.readyState === WebSocket.OPEN) this.socket.send('ping');
      }, this.pingIntervalValue * 1000);
    },

    stopHeartbeat() {
      if (!this.pingInterval) return
      clearInterval(this.pingInterval);
      this.pingInterval = undefined;
    },

    reconnect() {
      if (this.reconnectAttempts >= 5) {
        console.warn("[WebSocket] 已达到最大重连次数");
        return;
      }
      const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 30000); // 指数退避
      console.log(`[WebSocket] ${delay / 1000} 秒后尝试重连`);

      this.reconnectTimeout = window.setTimeout(() => {
        this.reconnectAttempts++;

        const userStore = useUserStore()
        this.connect(userStore.auth.token!); // 重新连接
      }, delay);
    },

    disconnect() {
      if (this.socket) {
        this.socket.close();
        this.socket = undefined;
      }
      this.stopHeartbeat();
    }
  }
})

