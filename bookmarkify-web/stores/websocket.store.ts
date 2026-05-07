import { defineStore } from 'pinia'
import { SocketTypes, type SocketMessage } from '@typing'
import { useAuthStore } from './auth.store'

// WebSocket 相关的 Pinia Store（负责建立连接、心跳、重连和消息分发）
export const useWebSocketStore = defineStore('socket', {
  // WebSocket 一般不需要持久化（运行时连接即可）
  persist: false,

  // 存放 WebSocket 相关状态
  state: () => ({
    // 当前 WebSocket 实例
    socket: undefined as WebSocket | undefined,
    // 当前连接所使用的 token,用于在 token 变化时强制重建连接
    currentToken: '' as string,
    // 是否为主动断开(主动断开后不会自动重连)
    manualClose: false,
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
      // 已有连接且 token 未变化:无需重连
      if (this.socket && this.currentToken === token) return
      // token 变更或显式重连:先关闭旧连接,避免遗留过期 socket
      if (this.socket) {
        this.manualClose = true
        try { this.socket.close() } catch { /* noop */ }
        this.socket = undefined
      }

      this.manualClose = false
      this.currentToken = token

      const url: string = `${useRuntimeConfig().public.wsBase}/ws?token=${token}`
      console.log(`[WebSocket] 连接: ${url}`)
      this.socket = new WebSocket(url)

      // 连接错误处理
      this.socket.onerror = (error) => {
        console.error('[WebSocket] 发生错误:', error)
        this.isConnected = false
      }

      // 连接关闭处理:非主动断开则尝试重连
      this.socket.onclose = () => {
        console.log('[WebSocket] 连接关闭')
        this.socket = undefined
        this.isConnected = false
        this.stopHeartbeat()
        if (!this.manualClose) this.reconnect()
      }

      // 连接成功处理
      this.socket.onopen = () => {
        console.log('[WebSocket] 已连接..')
        this.isConnected = true
        this.reconnectAttempts = 0 // 重置重连次数
        this.startHeartbeat() // 开启心跳检测
      }

      // 接收消息处理:服务端 pong 等心跳帧不是 JSON,需先过滤
      this.socket.onmessage = (event) => {
        const raw = event.data
        if (typeof raw !== 'string' || raw === 'pong' || raw === 'ping') return
        let message: SocketMessage
        try {
          message = JSON.parse(raw) as SocketMessage
        } catch (err) {
          console.warn('[WebSocket] 收到非 JSON 消息,已忽略:', raw)
          return
        }
        console.log(`[WebSocket] 收到消息:${message.type}`)
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

        const authStore = useAuthStore()
        const token: string = authStore.account?.token ?? ''
        if (!token) return
        // 重连前清掉缓存 token,确保 connect 真的会建立新连接
        this.currentToken = ''
        this.connect(token)
      }, delay)
    },

    // 主动断开连接并清空 socket
    disconnect() {
      if (import.meta.server) return
      this.manualClose = true
      if (this.reconnectTimeout) {
        clearTimeout(this.reconnectTimeout)
        this.reconnectTimeout = undefined
      }
      this.stopHeartbeat()
      if (this.socket) {
        try { this.socket.close() } catch { /* noop */ }
        this.socket = undefined
      }
      this.currentToken = ''
      this.isConnected = false
      this.reconnectAttempts = 0
    },
  },
})
