import { defineStore } from 'pinia'
import { SocketTypes, type SocketMessage } from '@typing'

// WebSocket 相关的 Pinia Store（负责建立连接、心跳、重连和消息分发）
export const useWebSocketStore = defineStore('socket', {
  // WebSocket 一般不需要持久化（运行时连接即可）
  persist: false,

  // 存放 WebSocket 相关状态
  state: () => ({
    // 当前 WebSocket 实例
    socket: undefined as WebSocket | undefined,
    // 预留的消息处理映射（按类型分发回调）
    actions: new Map() as Map<SocketTypes, Function>,
    // 心跳定时器句柄
    pingInterval: undefined as number | undefined,
    // 重连定时器句柄
    reconnectTimeout: undefined as number | undefined,
    // WebSocket 是否在线
    isConnected: false,
    // 已尝试重连的次数
    reconnectAttempts: 0,
    // 心跳间隔（秒）
    pingIntervalValue: 5,
    // 是否输出调试日志
    debug: false,
  }),

  // WebSocket 连接、心跳及重连相关动作
  actions: {
    // 建立 WebSocket 连接
    connect(token: string) {
      // 仅在客户端环境运行
      if (import.meta.server) return
      // 已有连接时不重复连接
      if (this.socket) return

      const url: string = `${useRuntimeConfig().public.wsBase}/ws?token=${token}`
      console.log(`[WebSocket] 连接: ${url}`)
      this.socket = new WebSocket(url)

      // 连接错误处理
      this.socket.onerror = (error) => {
        console.error('[WebSocket] 发生错误:', error)
        this.isConnected = false
      }

      // 连接关闭处理
      this.socket.onclose = () => {
        console.log('[WebSocket] 连接关闭')
        this.socket = undefined
        this.isConnected = false
        this.stopHeartbeat() // 停止心跳
      }

      // 连接成功处理
      this.socket.onopen = () => {
        console.log('[WebSocket] 已连接..')
        this.isConnected = true
        this.reconnectAttempts = 0 // 重置重连次数
        this.startHeartbeat() // 开启心跳检测
      }

      // 接收消息处理
      this.socket.onmessage = (event) => {
        const message = JSON.parse(event.data) as SocketMessage
        console.log('[WebSocket] 收到消息:', message)
        if (message.type === SocketTypes.HOME_ITEM_UPDATE) {
          const bookmarkStore = useBookmarkStore()
          bookmarkStore.updateOneBookmarkCell(message.data)
        }
      }
    },

    // 开启心跳检测，定期向服务端发送 ping
    startHeartbeat() {
      if (import.meta.server) return
      // 先清除之前的心跳，避免多次定时器叠加
      this.stopHeartbeat()
      this.pingInterval = window.setInterval(() => {
        if (this.debug) console.log('[WebSocket] 发送心跳')
        if (this.socket?.readyState === WebSocket.OPEN) this.socket.send('ping')
      }, this.pingIntervalValue * 1000)
    },

    // 停止心跳检测
    stopHeartbeat() {
      if (!this.pingInterval) return
      clearInterval(this.pingInterval)
      this.pingInterval = undefined
    },

    // 按指数退避策略进行重连
    reconnect() {
      if (import.meta.server) return
      if (this.reconnectAttempts >= 5) {
        console.warn('[WebSocket] 已达到最大重连次数')
        return
      }

      // 指数退避：1s, 2s, 4s, 8s, ...，最大不超过 30s
      const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 30000)
      console.log(`[WebSocket] ${delay / 1000} 秒后尝试重连`)

      this.reconnectTimeout = window.setTimeout(() => {
        this.reconnectAttempts++

        const userStore = useUserStore()
        const token: string = userStore.account?.token ?? ''
        this.connect(token) // 重新连接
      }, delay)
    },

    // 主动断开连接并清空 socket
    disconnect() {
      if (import.meta.server) return
      if (this.socket) {
        this.socket.close()
        this.socket = undefined
      }
    },
  },
})
