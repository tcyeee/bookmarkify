package top.tcyeee.bookmarkify.config.monitor

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
        if (!tracker.onError()) return
        dispatch(event)
    }

    private fun dispatch(event: ILoggingEvent) {
        val title = "⚠️ bookmarkify-api 连续报错"
        val desp = buildString {
            append("**Logger**: `${event.loggerName}`\n\n")
            append("**Message**: ${event.formattedMessage.take(300)}\n\n")
            event.throwableProxy?.let { append("**Exception**: `${it.className}: ${it.message}`\n\n") }
            append("触发条件：${windowMinutes} 分钟内连续 ${threshold} 条 ERROR 级别日志\n\n")
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
