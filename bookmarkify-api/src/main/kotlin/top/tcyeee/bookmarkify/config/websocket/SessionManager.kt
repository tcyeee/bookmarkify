package top.tcyeee.bookmarkify.config.websocket

import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.RoleEnum
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * @author tcyeee
 * @date 3/15/25 21:06
 */
object SessionManager {
    // Spring 的 WebSocketSession 不是线程安全的；并发 send 会触发
    // IllegalStateException("TEXT_PARTIAL_WRITING")，所以包一层 decorator 序列化写入。
    private const val SEND_TIME_LIMIT_MS = 5_000
    private const val BUFFER_SIZE_LIMIT = 64 * 1024

    /**
     * 同一 (realm, uid) 允许并存的连接数上限。
     *
     * 正常用户就是几个标签页，给到 8 足够宽松；它存在的意义只是防止「客户端疯狂重连而
     * afterConnectionClosed 没被触发」这类异常情况把连接句柄攒到没边。超出时踢最旧的那条。
     */
    private const val MAX_SESSIONS_PER_KEY = 8

    // key 为 "realm:uid"。USER 与 ADMIN 同属一个 user_info id 空间，若仅用 uid 作 key，
    // 同一账号同时连 web(USER) 与管理端(ADMIN) 会互相挤掉，故按 realm 命名空间隔离。
    //
    // value 是**一组**连接而不是一条：一个用户开多个标签页是常态，而每个标签页各建一条 WS。
    // 早先这里是 `put` 覆盖 + 关掉旧连接，等于后开的标签页把先开的踢下线；前端 onclose 又会
    // 无条件重连（且 onopen 把退避计数归零），于是两个标签页会以约 1s 的节奏无限互踢，各自
    // 有一半时间不在线，HOME_ITEM_UPDATE 推给谁全看那一刻谁占着槽位，推丢了也没有补偿。
    // 多标签页同步本来就是期望行为（HOME_DIR_UPDATE 这个消息类型正是为它而拆的），改为广播。
    private val sessions = ConcurrentHashMap<String, CopyOnWriteArrayList<WebSocketSession>>()

    fun add(session: WebSocketSession) {
        val key = key(session)
        val wrapped: WebSocketSession =
            ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT)
        val evicted = mutableListOf<WebSocketSession>()
        val total = sessions.compute(key) { _, current ->
            val list = current ?: CopyOnWriteArrayList()
            list.add(wrapped)
            while (list.size > MAX_SESSIONS_PER_KEY) evicted.add(list.removeAt(0))
            list
        }!!.size
        // 关闭挪到 compute 之外：close() 会走网络 IO，而 compute 期间持有该 bin 的锁
        evicted.forEach { prior ->
            log.warn("[WEBSOCKET] session limit reached for key=$key, closing oldest")
            runCatching { prior.close() }
                .onFailure { log.warn("[WEBSOCKET] close evicted session failed for key=$key: ${it.message}") }
        }
        log.info("[WEBSOCKET] new session key=$key sessions=$total keys=${sessions.size}")
    }

    fun remove(session: WebSocketSession) = drop(key(session), session.id)

    /**
     * 推送给该用户**当前全部**在线连接。
     *
     * 单条连接发送失败不影响其余连接：失败的那条就地关闭并摘除，否则它会一直留在表里，
     * 每次推送都白等一次 [SEND_TIME_LIMIT_MS]。
     */
    fun send(type: SocketMsgType, realm: String, uid: String, content: Any) {
        val key = "$realm:$uid"
        val targets = sessions[key]
        if (targets.isNullOrEmpty()) {
            // 用户不在线时推送就是丢的（没有离线队列）——这条日志是事后排查"书签一直转圈"的关键线索。
            // 客户端侧的兜底是重连后主动重新拉取布局。
            log.info("[WEBSOCKET] ${type.name} dropped: no online session for $key")
            return
        }
        // 序列化一次，所有连接共用；TextMessage 不可变，跨 session 复用是安全的
        val payload = Message(type, content).msg()
        var sent = 0
        // CopyOnWriteArrayList 的迭代是快照，遍历期间的增删不会抛 ConcurrentModificationException
        for (target in targets) {
            runCatching { target.sendMessage(payload) }
                .onSuccess { sent++ }
                .onFailure {
                    log.warn("[WEBSOCKET] send failed, dropping session key=$key: ${it.message}")
                    runCatching { target.close() }
                    drop(key, target.id)
                }
        }
        log.info("[WEBSOCKET] ${type.name} sent to $key: $sent/${targets.size}")
    }

    /**
     * 取回某条原始 session 对应的并发安全包装。
     *
     * 处理入站消息（心跳 pong）的线程与推送线程是两个线程，直接往原始 session 上写会与推送
     * 撞车触发 TEXT_PARTIAL_WRITING —— 那正是 decorator 要解决的问题，回帧必须走同一个包装。
     */
    fun wrapperOf(session: WebSocketSession): WebSocketSession? =
        sessions[key(session)]?.firstOrNull { it.id == session.id }

    /** 按 id 摘除一条连接；该 key 下已无连接时连 key 一起删掉，避免键无限累积。 */
    private fun drop(key: String, sessionId: String) {
        sessions.computeIfPresent(key) { _, list ->
            // 必须用 removeIf：CopyOnWriteArrayList 的迭代器不支持 remove()，
            // 而 Kotlin 的 removeAll{...} 扩展底层正是走迭代器
            list.removeIf { it.id == sessionId }
            if (list.isEmpty()) null else list
        }
    }

    fun uid(session: WebSocketSession) = session.attributes.getValue("uid").toString()
    private fun realm(session: WebSocketSession) =
        session.attributes["realm"]?.toString() ?: RoleEnum.USER.name
    private fun key(session: WebSocketSession) = "${realm(session)}:${uid(session)}"
}
