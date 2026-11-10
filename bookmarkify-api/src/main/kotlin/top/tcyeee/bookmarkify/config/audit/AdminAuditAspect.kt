package top.tcyeee.bookmarkify.config.audit

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.entity.AdminActionLogEntity
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IAdminActionLogService
import top.tcyeee.bookmarkify.utils.StpKit

/**
 * 把带 [AdminAudit] 的后台接口调用落成一条 `admin_action_log`。
 *
 * ## 为什么是切面而不是在每个方法里写一行
 *
 * 审计要记的六样东西（操作者、动作、目标、入参、成败、耗时）在每个接口里都一模一样，而其中
 * 「成败」必须包住异常才拿得到 —— 手写就是每个方法一个 try/finally，二十多处，且漏掉一处
 * 没有任何症状。切面还让"加审计"这件事变成一行注解，新增后台接口时的成本接近零。
 *
 * ## 两条刻意的设计
 *
 * 1. **失败也记**。被拒绝的删除、报错的清理同样是审计事实 —— 「管理员试过但没成功」与
 *    「管理员没试过」是两件完全不同的事，而只记成功的审计表无法区分它们。
 * 2. **审计失败绝不影响业务**。写入在 `IAdminActionLogService.record` 里包了 `runCatching`，
 *    所以这里只负责把现场拼好。反过来的设计（审计写不成就让操作失败）在一个单实例、
 *    无审计合规要求的系统里是赔本的：它把一张观测表变成了业务可用性的单点。
 */
@Aspect
@Component
class AdminAuditAspect(
    private val adminActionLogService: IAdminActionLogService,
    private val userMapper: UserMapper,
    private val objectMapper: ObjectMapper,
) {

    @Around("@annotation(audit)")
    fun around(joinPoint: ProceedingJoinPoint, audit: AdminAudit): Any? {
        val startedAt = System.currentTimeMillis()
        val result = runCatching { joinPoint.proceed() }
        record(joinPoint, audit, startedAt, result.exceptionOrNull())
        return result.getOrThrow()
    }

    private fun record(joinPoint: ProceedingJoinPoint, audit: AdminAudit, startedAt: Long, error: Throwable?) {
        val signature = joinPoint.signature as MethodSignature
        val request = currentRequest()
        // 操作者在 proceed **之后**才取：后台登录接口的会话正是在那次调用里建立的，
        // 提前取只会得到 null，而"谁登录了后台"恰恰是这张表最该回答的问题之一
        val operatorId = runCatching { StpKit.ADMIN.loginIdAsString }.getOrNull()
        adminActionLogService.record(
            AdminActionLogEntity(
                action = audit.value,
                handler = "${signature.declaringType.simpleName}.${signature.name}",
                httpMethod = request?.method,
                path = request?.let { it.requestURI + (it.queryString?.let { q -> "?$q" } ?: "") },
                targetId = pathVariable(signature, joinPoint.args),
                params = summarize(signature, joinPoint.args),
                success = error == null,
                errorMsg = error?.let { "${it.javaClass.simpleName}: ${it.message}" }?.take(500),
                operatorId = operatorId,
                operatorName = operatorId?.let { id -> runCatching { userMapper.selectById(id)?.nickName }.getOrNull() },
                durationMs = System.currentTimeMillis() - startedAt,
            )
        )
    }

    /** 第一个 `@PathVariable` 的值 —— 后台的约定里它就是被操作对象的 id（pageId / siteId / id） */
    private fun pathVariable(signature: MethodSignature, args: Array<Any?>): String? {
        val annotations = signature.method.parameterAnnotations
        return args.indices.firstOrNull { i ->
            annotations.getOrNull(i)?.any { it is PathVariable } == true
        }?.let { args[it]?.toString() }
    }

    /**
     * 入参摘要。
     *
     * 逐个参数序列化、失败就退回类名：后台入参里有 `MultipartFile`、`HttpServletResponse`
     * 这类压根不该（也不能）进 JSON 的东西，一次整体序列化会因为其中一个参数而丢掉全部现场。
     */
    /** 入参摘要 + 凭据打码，判据在 [AuditParamRedactor]（纯函数，单测钉住） */
    private fun summarize(signature: MethodSignature, args: Array<Any?>): String? {
        val keep = args.withIndex()
            .filterNot { (_, v) -> v is MultipartFile || v is HttpServletRequest || v is HttpServletResponse }
        return AuditParamRedactor.summarize(
            names = keep.map { (i, _) -> signature.parameterNames.getOrNull(i) },
            values = keep.map { (_, v) -> v },
            mapper = objectMapper,
        )
    }

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
