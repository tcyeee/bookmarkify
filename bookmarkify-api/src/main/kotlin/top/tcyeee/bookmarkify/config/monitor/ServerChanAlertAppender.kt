package top.tcyeee.bookmarkify.config.monitor

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import cn.hutool.http.HttpUtil
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 挂在 root logger 上的 Logback appender：任意 ERROR 级别日志（由 XML 里的
 * [ch.qos.logback.classic.filter.ThresholdFilter] 过滤）连续出现达到阈值时，
 * 通过 Server 酱推送一条微信通知。
 *
 * 覆盖面刻意选**全局**、不挑具体链路：现有报错分散在 `GlobalExceptionHandler`、四个
 * `@Async` 事件监听器、`@Scheduled` 任务的默认错误处理器、`SingleInstanceGuard` 等十几处，
 * 逐一埋点必然会漏；但它们无一例外都会打一条 ERROR 级别日志，挂在 root 上等于一次性接管
 * 全部这些路径，以后新增的报错路径也不需要再接线。
 *
 * 判断逻辑在 [ConsecutiveErrorTracker]（纯类，可单测，不依赖 Logback）；这个类只做三件事：
 * 从 XML 读配置（`sendKey` 留空即整体关闭）、把实际的 HTTP 调用丢到独立线程去做（绝不能在
 * 打日志的业务线程上做同步网络 IO），以及防止推送失败时的报错又打回自己形成递归。
 */
class ServerChanAlertAppender : AppenderBase<ILoggingEvent>() {

    // Logback 通过 XML 子标签按 JavaBean setter 反射赋值，字段必须是 var + 隐式 public setter
    var sendKey: String = ""
    var threshold: Int = 3
    var windowMinutes: Long = 5
    var cooldownMinutes: Long = 30

    private lateinit var tracker: ConsecutiveErrorTracker
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "serverchan-alert").apply { isDaemon = true }
    }

    override fun start() {
        tracker = ConsecutiveErrorTracker(
            threshold = threshold,
            windowMillis = TimeUnit.MINUTES.toMillis(windowMinutes),
            cooldownMillis = TimeUnit.MINUTES.toMillis(cooldownMinutes),
        )
        super.start()
    }

    override fun append(event: ILoggingEvent) {
        if (sendKey.isBlank()) return
        // 防御性保护：这个包本身不产生任何日志，但万一以后有人在这加了一条 log.error，
        // 绝不能让"推送失败"反过来又被自己计数，那样一次网络抖动会自我放大成无限重试。
        if (event.loggerName.startsWith(ALERT_LOGGER_PREFIX)) return
        if (!qualifies(event)) return
        if (!tracker.onError()) return
        dispatch(event)
    }

    /**
     * 哪些事件参与"连续报错"计数。
     *
     * XML 里的 `ThresholdFilter` 现在放到 WARN，因为 appender 必须**看得到** WARN 才可能挑出
     * 其中带 [LogMarkers.INFRA] 的那几条；真正的判据在这里，不在过滤器上：
     *
     * - `ERROR` 及以上：一律计数（与从前一致）；
     * - `WARN` + `INFRA` 标记：计数。这是本次新增的那一类 —— "我方基础设施坏了"刻意打成 warn
     *   （现场另有更完整的记录，且调用量大），但它恰恰是最该有人知道的故障；
     * - 其余 `WARN`：不计数。这一级有 120 多处调用点，大多是正常控制流里的提示
     *   （巡检保留原内容、清理了多少行日志），整条纳入等于把告警变成噪音发生器。
     */
    private fun qualifies(event: ILoggingEvent): Boolean =
        event.level.isGreaterOrEqual(Level.ERROR) || LogMarkers.isInfra(event.markerList)

    private fun dispatch(event: ILoggingEvent) {
        val title = "⚠️ bookmarkify-api 连续报错"
        val desp = buildString {
            append("**Level**: ${event.level}${if (LogMarkers.isInfra(event.markerList)) " (INFRA)" else ""}\n\n")
            append("**Logger**: `${event.loggerName}`\n\n")
            append("**Message**: ${event.formattedMessage.take(300)}\n\n")
            event.throwableProxy?.let { append("**Exception**: `${it.className}: ${it.message}`\n\n") }
            append("触发条件：${windowMinutes} 分钟内连续 ${threshold} 条 ERROR / INFRA 级别日志\n\n")
            append("请尽快 `docker logs bookmarkify-api` 或后台看板确认现场。")
        }
        executor.submit {
            runCatching {
                HttpUtil.createPost("https://sctapi.ftqq.com/$sendKey.send")
                    .form("title", title)
                    .form("desp", desp)
                    .timeout(5000)
                    .execute()
            }.onFailure {
                // 故意不走 SLF4J：这个 appender 本身挂在 root 上收 ERROR，用同一条日志管道
                // 报告"推送失败"就是在制造上面那条防御要挡的递归，直接写 stderr 更安全。
                System.err.println("[ServerChanAlertAppender] 推送失败: ${it.message}")
            }
        }
    }

    override fun stop() {
        executor.shutdown()
        super.stop()
    }

    companion object {
        private val ALERT_LOGGER_PREFIX = ServerChanAlertAppender::class.java.packageName
    }
}
