package top.tcyeee.bookmarkify.config.event

/**
 * 书签异步解析事件。
 *
 * 由 BookmarkServiceImpl / AppInit 发布，[BookmarkParseEventListener] 在异步线程池中消费，
 * 取代原 Kafka 消息（生产者与消费者本就在同一进程内，队列纯属多余的中转）。
 *
 * 发布时机均在相关数据库写入提交之后，因此监听器读取到的记录一定可见。
 */

/** 解析书签并保存（不通知用户）。对应原 BOOKMARK_PARSE，用于启动初始化与定时对账。 */
data class BookmarkParseEvent(val bookmarkId: String)

/** 解析已存在的书签，完成后通过 WebSocket 推送给用户。对应原 PARSE_NOTICE_EXISTING（单个添加）。 */
data class BookmarkParseAndNoticeEvent(
    val uid: String,
    val bookmarkId: String,
    val userLinkId: String,
    val nodeLayoutId: String,
)

/** 通过网址解析书签并重新绑定用户自定义书签，完成后推送。对应原 BOOKMARK_PARSE_AND_RESET_USER_ITEM（批量导入）。 */
data class BookmarkParseAndResetUserItemEvent(
    val uid: String,
    val rawUrl: String,
    val userLinkId: String,
    val layoutNodeId: String,
)

/**
 * 抓取成功后的元数据富化：分类打标 + NSFW 判定，都要调 DeepSeek。
 *
 * 单独成一个事件（而不是接在解析流程末尾）是因为这两件事**用户看不到**，却各要一次 10s 的
 * 外部往返。挂在解析线程上就等于让每条书签多占用解析池 20s，而解析池的吞吐直接决定
 * 「加一个书签要转多久圈」。跑在自己的线程池上，慢一点也不影响任何人。
 */
data class BookmarkEnrichEvent(val bookmarkId: String)
