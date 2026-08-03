import { defineStore } from 'pinia'
import { SocketTypes, type SocketMessage, type ShareStatusChangedMessage } from '@typing'
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
    // 是否因页面进入 bfcache 而暂停连接(与 manualClose 语义不同:恢复时应立即重连,不走退避)
    pausedForBfcache: false,
    // 生命周期监听器(pagehide/pageshow/online/visibilitychange)是否已注册(避免 connect() 重复调用时重复注册)
    listenersRegistered: false,
    // 本页面是否成功连接过。用于区分「首次连接」与「重连」:只有重连需要补拉布局,
    // 首次连接时 plugins/auth.ts 已经拉过了
    hasEverConnected: false,
    // 最近一次收到**任何**帧(含 pong)的时间戳,由看门狗判定链路是否已经半开
    lastMessageAt: 0,
    // 看门狗定时器句柄
    watchdogInterval: undefined as number | undefined,
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
    // 最近一次收到的分享状态变化推送（供「我的分享」面板 watch 后就地更新，无需刷新页面）
    lastShareStatusChange: null as ShareStatusChangedMessage['data'] | null,
  }),

  // WebSocket 连接、心跳及重连相关动作
  actions: {
    // 建立 WebSocket 连接
    connect(token: string) {
      // 仅在客户端环境运行
      if (import.meta.server) return
      this.registerBfcacheListeners()
      // 已有连接且 token 未变化:无需重连
      if (this.socket && this.currentToken === token) return
      // token 变更或显式重连:先关闭旧连接,避免遗留过期 socket
      // 先置空 onclose,防止异步触发时 manualClose 已被重置为 false 导致误重连
      if (this.socket) {
        this.socket.onclose = null
        this.manualClose = true
        try { this.socket.close() } catch { /* noop */ }
        this.socket = undefined
      }

      this.manualClose = false
      this.currentToken = token

      const wsBase = useRuntimeConfig().public.wsBase
      const url: string = `${wsBase}/ws?token=${token}`
      // 不要把 token 打到控制台：会话凭据泄露到浏览器日志/录屏中
      console.log(`[WebSocket] 连接: ${wsBase}/ws`)
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
        this.lastMessageAt = Date.now()
        this.startHeartbeat() // 开启心跳检测
        // 断线期间服务端推的消息是**直接丢弃**的(没有离线队列,见 SessionManager.send),
        // 重连成功后必须主动跟服务端对一次账,否则那期间解析完成的书签会永远停在 LOADING。
        // 首次连接不做:plugins/auth.ts 刚拉过,重复拉一次纯属浪费。
        if (this.hasEverConnected) {
          console.log('[WebSocket] 重连成功,补拉桌面布局以对齐断线期间错过的推送')
          useBookmarkStore().refresh('WebSocket 重连')
        }
        this.hasEverConnected = true
      }

      // 接收消息处理:服务端 pong 等心跳帧不是 JSON,需先过滤
      this.socket.onmessage = (event) => {
        const raw = event.data
        // 收到任何一帧都算链路活着——pong 尤其重要,它是看门狗唯一的正向信号
        this.lastMessageAt = Date.now()
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
          console.log(`[WebSocket] HOME_ITEM_UPDATE: nodeId=${message.data.id}, nodeType=${message.data.type}`)
          const bookmarkStore = useBookmarkStore()
          bookmarkStore.replaceContent(message.data)
          bookmarkStore.clearResolutionWatch(message.data.id)
        } else if (message.type === SocketTypes.HOME_DIR_UPDATE) {
          console.log(`[WebSocket] HOME_DIR_UPDATE: folderId=${message.data.id}, children=${message.data.children?.length ?? 0}`)
          useBookmarkStore().replaceFolder(message.data)
        } else if (message.type === SocketTypes.HOME_LAYOUT_REFRESH) {
          console.log('[WebSocket] HOME_LAYOUT_REFRESH: 整树重置')
          useBookmarkStore().setLayout(message.data)
        } else if (message.type === SocketTypes.SHARE_STATUS_CHANGED) {
          this.lastShareStatusChange = message.data
          useToastStore().warning(`您分享的内容未通过审核，已下架。原因：${message.data.rejectReason ?? '未知'}`)
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

      // 只发不收的心跳什么都证明不了:移动网络切换、NAT 表项超时、笔记本合盖之后,连接会变成
      // "半开"——对端已经没了,本端的 readyState 却还是 OPEN,send() 也不报错。于是 onclose
      // 永远不触发、重连永远不发生、推送永远收不到,表现就是书签一直转圈。
      // 服务端现在会回 pong(WebSocketHandler.handleTextMessage),这里据此判定链路死活:
      // 连续 3 个心跳周期没收到任何一帧就主动 close(),把它转成一次正常的 onclose → 重连。
      this.watchdogInterval = window.setInterval(() => {
        if (this.socket?.readyState !== WebSocket.OPEN) return
        const silentMs = Date.now() - this.lastMessageAt
        if (silentMs < this.pingIntervalValue * 1000 * 3) return
        console.warn(`[WebSocket] ${Math.round(silentMs / 1000)}s 未收到任何帧,判定连接已失效,主动断开重连`)
        // 不置 manualClose:要的正是 onclose 里那条自动重连
        try { this.socket.close() } catch { /* noop */ }
      }, this.pingIntervalValue * 1000)
    },

    // 停止心跳检测
    stopHeartbeat() {
      if (this.pingInterval) {
        clearInterval(this.pingInterval)
        this.pingInterval = undefined
      }
      if (this.watchdogInterval) {
        clearInterval(this.watchdogInterval)
        this.watchdogInterval = undefined
      }
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
      // 主动断开通常意味着退出登录/切换账号,下次连上是一次全新会话:
      // 此时的布局由登录流程负责拉取,不该再由 onopen 当作"重连"补拉一次
      this.hasEverConnected = false
      this.lastMessageAt = 0
    },

    // 注册页面/网络生命周期监听(只注册一次):
    // - pagehide/pageshow: 进入与恢复 bfcache
    // - online/visibilitychange: 断网恢复与标签页重新可见
    registerBfcacheListeners() {
      if (!import.meta.client || this.listenersRegistered) return
      this.listenersRegistered = true

      window.addEventListener('pagehide', (event) => {
        if (event.persisted) this.pauseForBfcache()
      })

      window.addEventListener('pageshow', (event) => {
        if (event.persisted) this.resumeFromBfcache()
      })

      // 退避预算只有 5 次(累计约 31s),真断网超过这个数就再也不会自己回来了。
      // 而"网络恢复"和"标签页重新可见"是两个明确的、值得立刻重试的外部信号——
      // 没有它们,用户合上笔记本再打开,这个页面的 WebSocket 就是永久死的。
      window.addEventListener('online', () => this.forceReconnect('网络已恢复'))

      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') this.forceReconnect('标签页重新可见')
      })
    },

    // 由外部信号(网络恢复/页面重新可见)触发的立即重连:不走退避,也不消耗重连预算。
    // 已连接或用户已主动断开时什么都不做。
    forceReconnect(reason: string) {
      if (!import.meta.client) return
      if (this.manualClose || this.pausedForBfcache) return
      if (this.socket?.readyState === WebSocket.OPEN || this.socket?.readyState === WebSocket.CONNECTING) return

      const token: string = useAuthStore().account?.token ?? ''
      if (!token) return // 未登录/已退出,没有可用会话

      console.log(`[WebSocket] ${reason},立即重连`)
      if (this.reconnectTimeout) {
        clearTimeout(this.reconnectTimeout)
        this.reconnectTimeout = undefined
      }
      this.reconnectAttempts = 0
      this.currentToken = '' // 清掉缓存 token,确保 connect 真的会建立新连接
      this.connect(token)
    },

    // 页面即将进入 bfcache:主动关闭连接,避免浏览器强制断开时打印错误日志
    pauseForBfcache() {
      if (!this.socket) return
      console.log('[WebSocket] 页面进入 bfcache,主动断开连接')
      this.pausedForBfcache = true
      // 先置空 onclose,避免触发 reconnect() 的退避重连
      this.socket.onclose = null
      this.stopHeartbeat()
      if (this.reconnectTimeout) {
        clearTimeout(this.reconnectTimeout)
        this.reconnectTimeout = undefined
      }
      try { this.socket.close() } catch { /* noop */ }
      this.socket = undefined
      this.isConnected = false
    },

    // 页面从 bfcache 恢复:立即重连,不走指数退避,也不消耗 reconnectAttempts 预算
    resumeFromBfcache() {
      if (!this.pausedForBfcache) return
      this.pausedForBfcache = false

      const authStore = useAuthStore()
      const token: string = authStore.account?.token ?? ''
      if (!token) return // 页面处于后台期间用户已退出登录

      console.log('[WebSocket] 页面从 bfcache 恢复,立即重连')
      this.reconnectAttempts = 0
      this.currentToken = ''
      this.connect(token)
    },
  },
})
