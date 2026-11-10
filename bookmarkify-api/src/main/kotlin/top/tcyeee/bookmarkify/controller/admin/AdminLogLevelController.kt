package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.audit.AdminAudit
import top.tcyeee.bookmarkify.server.log.LogLevelService

/**
 * 运行时日志级别（系统管理 › 日志级别）。为什么不用 actuator，见 [LogLevelService]。
 *
 * 改级别是一个**会影响生产日志量**的操作，所以它和删页面一样带 `@AdminAudit`：
 * 日志量突然暴涨时，第一个要回答的问题就是"谁在什么时候打开了 DEBUG"。
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/log-level")
class AdminLogLevelController(private val logLevelService: LogLevelService) {

    @PostMapping("/list")
    fun list(): List<LogLevelService.LogLevelView> = logLevelService.list()

    @AdminAudit("临时调整日志级别")
    @PostMapping("/set")
    fun set(
        @RequestParam name: String,
        @RequestParam level: String,
        @RequestParam(defaultValue = "30") ttlMinutes: Long,
    ): LogLevelService.LogLevelView = logLevelService.set(name, level, ttlMinutes)

    @AdminAudit("恢复日志级别")
    @PostMapping("/revert")
    fun revert(@RequestParam name: String) = logLevelService.revert(name)
}
