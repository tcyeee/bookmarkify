package top.tcyeee.bookmarkify.controller

import io.swagger.v3.oas.annotations.Hidden
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 真正的健康检查。**部署门禁与容器探活都该用它。**
 *
 * ## 为什么从前的那道门禁等于没有
 *
 * `deploy-api.yml` 原先轮询的是 `GET /auth/track`，判据是 `curl -fsS` 能不能成功。问题是本项目
 * 用 HTTP 200 承载业务错误：线上实测**任何**路径（包括 `/nonexistent-xyz`）都返回
 * `200 {"ok":false,"code":101,"msg":"请先登录!"}`。也就是说那道门禁只证明了 Tomcat 在监听端口，
 * 数据库连不上、Redis 挂了、OSS 配错、`AppInit` 跑挂——全都照样算"部署成功"，还会推一条
 * 成功通知。顺带，`/auth/track` 这个端点本身早就随匿名会话一起删掉了，它靠的正是那个兜底响应。
 *
 * ## 判据
 *
 * 只探**进程启动之后必须一直可用**的两样东西：PostgreSQL 与 Redis。它们是任何一条请求路径的
 * 共同前提（会话在 Redis，数据在 PG），任一不通，服务对用户就是全坏的。
 *
 * 刻意**不**探 scrapper / OSS / DeepSeek：那些是下游，挂了只影响部分功能，而且本服务对它们的
 * 失败是有设计的（E307 留 PENDING、巡检熔断、`reclaim-orphans` 开关）。把它们纳入健康判据，
 * 等于让第三方的抖动阻塞我方发版，甚至在 Compose 探活下把一个工作正常的容器反复重启。
 *
 * ## 为什么挂在 /internal/ 下
 *
 * nginx 把 `/api/` 前缀下的一切整体转发到本服务，只对 `/api/internal/` 显式 `return 404`（见
 * `deploy/nginx/bookmakify.cc.conf`）。放在这个前缀下，公网够不到，而 CI 与 Compose 都是在
 * 宿主机上直连 `127.0.0.1:7001`，照样用得上。健康信息不该对公网开放——"哪个依赖挂了"是
 * 攻击者最想知道的那类信息。该前缀另已被排除在 Sa-Token 鉴权之外（见 `SaTokenConfigure`）。
 */
@Hidden
@RestController
@RequestMapping("/internal")
class InternalHealthController(
    private val dataSource: DataSource,
    private val redisTemplate: RedisTemplate<String, Any>,
) {

    private val log = LoggerFactory.getLogger(InternalHealthController::class.java)

    /**
     * `200` = 两个依赖都通；`503` = 至少一个不通，响应体指明是哪一个。
     *
     * 返回 `String` 而不是对象：`GlobalExceptionHandler` 的 `ResponseBodyAdvice` 会把对象包进
     * `ResultWrapper`（那是给业务接口用的约定），而这个端点的消费方是 `curl -f` 和容器探活，
     * 它们只认状态码，响应体保持最朴素的形状更好读。字符串体会被原样放过。
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<String> {
        val db = check("db") { dataSource.connection.use { it.isValid(PROBE_TIMEOUT_SECONDS) } }
        // 显式 RedisCallback：RedisTemplate.execute 有 SessionCallback 重载，裸 lambda 会歧义
        val redis = check("redis") {
            redisTemplate.execute(RedisCallback { it.ping() != null }) == true
        }
        val ok = db == null && redis == null
        val body = """{"ok":$ok,"db":${quote(db)},"redis":${quote(redis)}}"""
        if (!ok) {
            // warn 而非 error：探活失败时调用方（CI / Compose）自己就会处置，这里重复报警只会
            // 在一次真故障里叠加几十条推送。真正的故障现场在各自组件自己的日志里
            log.warn("[health] 依赖不健康: db={}, redis={}", db ?: "up", redis ?: "up")
        }
        return ResponseEntity
            .status(if (ok) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
    }

    /** 探一项依赖：通了返回 null，没通返回失败原因（截断，避免把堆栈塞进响应体） */
    private fun check(name: String, probe: () -> Boolean): String? = runCatching {
        if (probe()) null else "$name probe returned false"
    }.getOrElse { it.message?.take(200) ?: it.javaClass.simpleName }

    private fun quote(failure: String?): String = if (failure == null) "\"up\"" else "\"down: $failure\""

    companion object {
        /**
         * 单项探测的超时（秒）。
         *
         * 取 2 秒：这个端点被定时调用（Compose 探活、CI 轮询），自己绝不能变成一个会挂住的请求。
         * 真正的依赖故障通常表现为连接被拒（立刻返回），而"连得上但很慢"在健康语义下同样算坏。
         */
        private const val PROBE_TIMEOUT_SECONDS = 2
    }
}
