package top.tcyeee.bookmarkify.controller

import io.swagger.v3.oas.annotations.Hidden
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.InputStreamResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.server.IOssObjectService
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 缓存代理的回源端点。**只给 nginx 用，不是公开 API。**
 *
 * ## 它为什么存在
 *
 * 私有桶的图片要给浏览器看就得签名，而签名 URL 会变；URL 一变浏览器缓存就失效，于是每个用户
 * 每次打开桌面都要为屏幕上每张图重付一次 OSS GET 请求费和一次图片处理费 —— 成本随
 * 「用户数 × 打开次数 × 书签数 × 尺寸档数」线性增长，四项都跟收入无关。
 *
 * 解法是在自家 nginx 上加一层缓存，对外发不带签名的永久地址。但 nginx 自己读不了私有桶，
 * 常规做法是给桶加一条按来源 IP 放行的 Bucket Policy —— 那条路在阿里云控制台上要求同时配
 * VPC 条件（服务器在腾讯云，永远不在那个 VPC 里，实测 403 `AccessDenied ... bucket acl`）。
 *
 * 于是改成让 nginx 回源到这里：SDK 手里就有 AK，私有桶本来就读得动，**桶的权限一个字都不用改**。
 * 回源只在 nginx 未命中时发生，而未命中对每个 (key, 尺寸) 一辈子只有一次，所以这一跳不在热路径上。
 *
 * ## 三道门
 *
 * 这个端点绕过了 Sa-Token（见 `SaTokenConfigure` 的 excludePathPatterns），所以自己得把门看住。
 * 每一道挡的都是不同的东西：
 *
 * 1. **共享密钥** —— 挡住公网。`bookmarkify.cc` 上那条 nginx 规则会把 `/api/` 前缀下的一切
 *    整体转发到本服务，也就是说这个路径事实上可以从公网够到。没有这道门，任何人都能绕开缓存直接触发
 *    OSS 读取和图片处理，**按次计费**，正是这套东西要消灭的开销。
 * 2. **账本校验** —— 挡住乱猜 key。只有 `oss_object` 里有记录的对象才给读，否则 404。
 * 3. **不可变校验** —— 挡住我们自己犯错。nginx 对这里的 200 响应缓存一年且下发
 *    `Cache-Control: immutable`，那是**不可撤销的承诺**；一个会被原地覆盖的 key（截图走的
 *    SOURCE_URL 寻址）漏到这里，改动就被永久钉死，且没有任何办法让已发出去的 URL 失效。
 */
@Hidden
@RestController
@RequestMapping("/internal/asset")
class InternalAssetController(
    private val ossObjectService: IOssObjectService,
) {

    private val log = LoggerFactory.getLogger(InternalAssetController::class.java)

    /**
     * nginx 与本服务之间的共享密钥。**留空 = 端点整体关闭**（一律 404）。
     *
     * 失败方向是刻意选的：漏配密钥时这条路不通，退回签名直连，只是没省到钱；
     * 而"漏配即放行"会把一个按次计费的接口裸露在公网上。
     */
    @Value("\${bookmarkify.aliyun.oss.proxy-token:}")
    private var proxyToken: String = ""

    @GetMapping("/{*key}")
    fun fetch(
        @PathVariable key: String,
        @RequestParam(required = false) w: Int?,
        @RequestParam(required = false) h: Int?,
        @RequestHeader(name = HEADER_TOKEN, required = false) token: String?,
    ): ResponseEntity<InputStreamResource> {
        if (proxyToken.isBlank() || !constantTimeEquals(proxyToken, token)) {
            log.warn("[fetch] 令牌不匹配, 拒绝: key={}", key)
            return ResponseEntity.notFound().build()
        }

        // `{*key}` 捕获的是带前导斜杠的整段剩余路径
        val objectKey = key.removePrefix("/").substringBefore('?')
        if (objectKey.isBlank() || objectKey.contains("..")) return ResponseEntity.notFound().build()

        val row = ossObjectService.findByKey(objectKey)
        if (row == null) {
            log.warn("[fetch] 账本里没有这个 key: {}", objectKey)
            return ResponseEntity.notFound().build()
        }
        if (!row.immutable) {
            // 走到这里说明上游哪里判错了：签名侧只会给不可变对象发代理地址
            log.error("[fetch] 拒绝可变对象走缓存代理, 它会被永久缓存: key={}, addressing={}", objectKey, row.addressing)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        // 尺寸也要在这里卡一遍，不能只靠 nginx 的白名单：这个端点是独立的攻击面，
        // 放任任意尺寸等于把"每个尺寸一次图片处理计费"的开关交出去
        val process = OssUtils.resizeStyle(w?.takeIf { it in ALLOWED_SIZES }, h?.takeIf { it in ALLOWED_SIZES })
            ?.takeIf { OssUtils.canImageProcess(row.mime, row.isVector, objectKey) }

        val obj = OssUtils.fetchObject(objectKey, process)
            ?: return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()

        val meta = obj.objectMetadata
        val contentType = runCatching { MediaType.parseMediaType(meta.contentType) }
            .getOrDefault(MediaType.APPLICATION_OCTET_STREAM)

        log.debug("[fetch] 回源成功: key={}, process={}, bytes={}", objectKey, process, meta.contentLength)
        return ResponseEntity.ok()
            .contentType(contentType)
            .contentLength(meta.contentLength)
            // 缓存策略由 nginx 统一下发（它才知道自己缓了多久），这里只声明可缓存，
            // 免得默认头里跑出个 no-store 把整层缓存废掉
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .body(InputStreamResource(obj.objectContent))
    }

    /**
     * 定长比较。密钥比对用 `==` 会因为提前返回而泄漏前缀匹配长度 —— 这里的密钥是长期有效的
     * 共享秘密，值得按秘密对待。
     */
    private fun constantTimeEquals(expected: String, actual: String?): Boolean {
        val a = expected.toByteArray()
        val b = (actual ?: "").toByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        const val HEADER_TOKEN = "X-Internal-Token"

        /**
         * 允许的缩放边长。与 nginx 的 `map` 白名单、以及签名侧实际签发的尺寸三处必须一致：
         * 64/256 = `SiteAssetResolver.renderSize(LIST/TILE)`，128 = 后台头像与对象预览，
         * 300 = 用户头像，640 = [OssUtils.COVER_WIDTH]。
         *
         * 不在名单里的尺寸退化成原图直出（多下几 KB），而不是报错 —— 与
         * [OssUtils.canImageProcess] 对 SVG/ICO 的处理同方向。
         */
        val ALLOWED_SIZES = setOf(64, 128, 256, 300, 640)
    }
}
