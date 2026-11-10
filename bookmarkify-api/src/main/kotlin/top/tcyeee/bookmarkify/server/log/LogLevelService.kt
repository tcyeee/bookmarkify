package top.tcyeee.bookmarkify.server.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType

/**
 * 运行时改日志级别。
 *
 * ## 为什么需要它
 *
 * 这套代码里有 **194 处 `log.debug`**，而生产 `root = INFO` —— 它们在线上等于不存在。
 * 这本身没问题（debug 的用途就是排查时才看），真正的问题是**没有打开它的办法**：
 * 要看一条 debug 就得改 `application.yml`、重新构建、走一遍 CI、重启容器，而排查往往需要
 * 的是"现在、就这一个类"。代价是那 194 行里的大部分从写下那天起就没被人读过一次。
 *
 * 常规解法是 Spring Boot Actuator 的 `/actuator/loggers`。这里刻意不用：nginx 把 `/api/` 前缀
 * 下的一切整体转发给本服务，装上 actuator 等于把一组管理端点挂到公网上（同 `TestController`
 * 被删掉的那个教训），而为它单独配一套端口与鉴权，比直接做成一个 ADMIN 领域的接口还麻烦。
 *
 * ## 为什么强制带 TTL
 *
 * 打开 DEBUG 之后忘记关是这类功能的默认结局，而后果不是"日志多一点"：生产的日志量会从
 * 每天几百 KB 跳到几百 MB，滚动策略的 30 天历史被几个小时冲掉 —— 等于顺手毁掉了排查别的问题
 * 所需的历史。所以这里没有"永久生效"这个选项：到点自动回到原级别，[MAX_TTL_MINUTES] 封顶。
 */
@Service
class LogLevelService {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 单线程定时器，负责到点回滚。daemon 线程：它绝不该阻止进程退出，
     * 而进程重启本身就会让所有级别回到配置文件里的值（这也是一道兜底）。
     */
    private val reverter = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "log-level-revert").apply { isDaemon = true }
    }

    /** 当前有哪些 logger 被临时改过，以及它们原来的级别（用于展示与回滚） */
    private val overrides = mutableMapOf<String, String>()

    /** 列出常用 logger 的当前生效级别。只列本项目的包与 root —— 框架的几百个 logger 不是排查对象 */
    fun list(): List<LogLevelView> {
        val context = context()
        return (listOf(Logger.ROOT_LOGGER_NAME) + DEFAULT_LOGGERS).map { name ->
            val logger = context.getLogger(name)
            LogLevelView(
                name = name,
                level = logger.level?.toString(),
                effectiveLevel = logger.effectiveLevel.toString(),
                revertTo = overrides[name],
            )
        }
    }

    /**
     * 临时把 [name] 的级别设成 [level]，[ttlMinutes] 分钟后自动回滚。
     *
     * @param level TRACE/DEBUG/INFO/WARN/ERROR/OFF；不认识的值直接拒绝，不静默当成 DEBUG
     */
    fun set(name: String, level: String, ttlMinutes: Long): LogLevelView {
        val target = Level.toLevel(level.uppercase(), null)
            ?: throw CommonException(ErrorType.E102, "无法识别的日志级别: $level")
        val ttl = ttlMinutes.coerceIn(1, MAX_TTL_MINUTES)
        val logger = context().getLogger(name)

        // 只在第一次改的时候记原值：连续调两次（DEBUG→TRACE）不该把"原级别"覆盖成上一次的临时值
        val original = overrides.getOrPut(name) { logger.level?.toString() ?: INHERIT }
        logger.level = target
        logger.info("[LogLevel] {} 临时设为 {}，{} 分钟后回到 {}", name, target, ttl, original)

        reverter.schedule({ revert(name) }, ttl, TimeUnit.MINUTES)
        return LogLevelView(
            name = name,
            level = target.toString(),
            effectiveLevel = target.toString(),
            revertTo = original,
            ttlMinutes = ttl,
        )
    }

    /** 立刻回滚一个 logger（TTL 到点时走的也是这里） */
    fun revert(name: String) {
        val original = overrides.remove(name) ?: return
        context().getLogger(name).level = if (original == INHERIT) null else Level.toLevel(original)
        logger.info("[LogLevel] {} 已回到 {}", name, original)
    }

    private fun context(): LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

    data class LogLevelView(
        val name: String,
        /** 显式设置的级别；为空表示继承父 logger */
        val level: String?,
        val effectiveLevel: String,
        /** 临时改过的话，到点会回到这个级别 */
        val revertTo: String? = null,
        val ttlMinutes: Long? = null,
    )

    companion object {
        private const val INHERIT = "INHERIT"

        /**
         * TTL 上限 24 小时。够长（跨过一次完整的巡检周期、一夜的定时任务），
         * 又短到"忘了关"最多只烧掉一天的日志量
         */
        const val MAX_TTL_MINUTES = 1440L

        /** 排查时真正会用到的几个入口。写死一份清单而不是扫全部 logger —— 后者有几百个 */
        val DEFAULT_LOGGERS = listOf(
            "top.tcyeee.bookmarkify",
            "top.tcyeee.bookmarkify.server.impl.BookmarkServiceImpl",
            "top.tcyeee.bookmarkify.server.impl.ApiServiceImpl",
            "top.tcyeee.bookmarkify.server.liveness.LivenessSweepService",
            "top.tcyeee.bookmarkify.server.asset",
            "top.tcyeee.bookmarkify.config.filter.PreRequestFilter",
        )
    }
}

/** 只为取 `ROOT` 这个常量名，避免和 logback 的 Logger 类型混用 */
private object Logger {
    const val ROOT_LOGGER_NAME = org.slf4j.Logger.ROOT_LOGGER_NAME
}
