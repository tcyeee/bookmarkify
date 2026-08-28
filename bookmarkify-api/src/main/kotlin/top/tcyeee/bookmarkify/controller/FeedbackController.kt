package top.tcyeee.bookmarkify.controller

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.throttle.Throttle
import top.tcyeee.bookmarkify.entity.FeedbackSubmitParams
import top.tcyeee.bookmarkify.server.IFeedbackService

/**
 * 公开的网站反馈收集接口，**无需登录** —— 调用方是外部 Agent / 集成脚本。
 *
 * `@SaIgnore` 让 [top.tcyeee.bookmarkify.config.filter.SaTokenConfigure] 的 USER 拦截器放行
 * （与 `/auth/track` 等匿名接口同一处理方式）。按 IP 节流防刷。
 */
@RestController
@Tag(name = "网站反馈组件（公开）")
@RequestMapping("/feedback")
class FeedbackController(
    private val feedbackService: IFeedbackService,
) {

    /** 当前可选的「所属产品」列表，供调用方核对取值 */
    @SaIgnore
    @GetMapping("/targets")
    @Operation(summary = "可选的所属产品列表")
    fun targets(): List<String> = feedbackService.listTargetNames()

    /** 提交一条反馈 */
    @SaIgnore
    @Throttle(3000, byIp = true)
    @PostMapping("/submit")
    @Operation(summary = "提交反馈")
    fun submit(@RequestBody params: FeedbackSubmitParams, request: HttpServletRequest): Boolean {
        feedbackService.submit(params, clientIp(request), request.getHeader("User-Agent"))
        return true
    }

    /** nginx 传的是 X-Real-IP；直连时退回 TCP 对端地址 */
    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader("X-Real-IP")?.takeIf { it.isNotBlank() } ?: request.remoteAddr
}
