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

/**
 * 抓取成功后补一张页面截图，作为书签详情面板的封面。
 *
 * 单独成事件、且跑在**单线程**的截图池上，理由和 [BookmarkEnrichEvent] 同源但更硬：
 * 截图强制走无头浏览器，而 scrapper 侧的 Chrome 由一把全局互斥锁串行化（生产容器
 * 内存 1GB，开不起第二个）。它既不能进解析主链路——那会让每条新增书签排队等浏览器，
 * 也不该多开线程去抢——多出来的线程只会堵在对端的锁上，白白耗掉 HTTP 读超时。
 *
 * 用户先拿到书签，封面晚几秒出现；抓不到就没有封面，前端本就按可选处理。
 */
data class BookmarkScreenshotEvent(val bookmarkId: String)
