package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.IdUtil
import com.aliyun.oss.OSS
import org.springframework.web.multipart.MultipartFile
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.model.GeneratePresignedUrlRequest
import com.aliyun.oss.model.ListObjectsV2Request
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.enums.FileType
import java.io.InputStream
import java.net.URI

/**
 * @author tcyeee
 * @date 12/29/25 13:43
 */
@Component
class OssUtils {

    @Value("\${bookmarkify.aliyun.oss.endpoint}")
    private lateinit var endpoint: String

    @Value("\${bookmarkify.aliyun.oss.access-key-id}")
    private lateinit var accessKeyId: String

    @Value("\${bookmarkify.aliyun.oss.access-key-secret}")
    private lateinit var accessKeySecret: String

    @Value("\${bookmarkify.aliyun.oss.bucket-name}")
    private lateinit var bucketName: String

    @Value("\${bookmarkify.aliyun.oss.domain-name}")
    private lateinit var domainName: String

    /**
     * 初始化OSS客户端及域名配置
     */
    @PostConstruct
    fun init() {
        log.debug("[init] 初始化OSS客户端: endpoint={}, bucket={}", endpoint, bucketName)
        ossClient = OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret)
        bucket = bucketName
        initDomain(endpoint, domainName, bucketName)
        log.debug("[init] OSS客户端初始化完成, domain={}", domain)
    }

    /**
     * 容器销毁时关闭OSS客户端
     */
    @PreDestroy
    fun destroy() {
        log.debug("[destroy] 关闭OSS客户端")
        ossClient.shutdown()
        log.debug("[destroy] OSS客户端已关闭")
    }

    companion object {
        private val log = LoggerFactory.getLogger(OssUtils::class.java)
        private lateinit var ossClient: OSS
        private lateinit var bucket: String
        private lateinit var domain: String
        private lateinit var customDomain: String

        /** 常规对象的签名有效期。key 可能被覆盖写，所以不能签太久 */
        const val DEFAULT_TTL_MILLIS: Long = 3600 * 1000

        /**
         * 内容寻址对象的签名有效期。
         *
         * 这类 key 由字节的 sha256 得出，内容永不改变 —— 换句话说 URL 不存在"过期后指向别的
         * 东西"的风险，短有效期换不来任何正确性，只是白白削掉缓存命中率。配合
         * [signWithResize] 的窗口对齐，同一天内同一张图拿到的是逐字节相同的 URL。
         */
        const val IMMUTABLE_TTL_MILLIS: Long = 24 * 3600 * 1000

        /**
         * 解析配置生成访问域名（支持自定义域名）
         */
        fun initDomain(endpoint: String, domainConfig: String, bucketName: String) {
            log.debug("[initDomain] endpoint={}, domainConfig={}, bucketName={}", endpoint, domainConfig, bucketName)
            val protocol = if (endpoint.startsWith("https://")) "https://" else "http://"
            if (domainConfig.isNotBlank()) {
                val d = if (domainConfig.startsWith("http")) domainConfig
                else "$protocol$domainConfig"
                domain = d.removeSuffix("/")
                customDomain = domain
                log.debug("[initDomain] 使用自定义域名: domain={}", domain)
            } else {
                val rawEndpoint = endpoint.removePrefix(protocol)
                domain = "$protocol$bucketName.$rawEndpoint"
                customDomain = ""
                log.debug("[initDomain] 使用默认OSS域名: domain={}", domain)
            }
        }

        /**
         * 把一条**资产引用**转成前端可直接用的限时签名地址。
         *
         * 这是全项目唯一该被展示层调用的入口。它要处理两种形态：
         * - **裸 object key**（scrapper 新契约的 `storageKey`，以及本服务自己写入的路径）
         * - **完整 URL**（`site_asset.storage_url` 里的存量数据，scrapper 改造前写入的）
         *
         * 分流依据只有"是不是以 http(s):// 开头"这一条，因此新旧两种形态可以在库里长期共存，
         * 不需要数据迁移、不需要停机，也不要求两个服务同时上线。
         *
         * 非本服务 OSS 的外链（例如资产只做了 PROBE、库里存的是源站地址）原样返回 —— 那种
         * 地址本来就是公开可访问的，签名反而会破坏它。
         *
         * @param ref object key 或完整 URL
         * @param size 目标边长；null 或 <=0 表示不缩放（矢量图必须走这条）
         * @param immutable 该对象的字节是否永不改变（内容寻址的对象即是）。为 true 时签发
         *   [IMMUTABLE_TTL_MILLIS] 的长效链接：对象不会变，短有效期换不来任何正确性，只是
         *   让浏览器和 CDN 更频繁地 cache miss，而每次回源都要付一次按次计费的 OSS 图片处理
         */
        fun signAsset(ref: String?, size: Int? = null, immutable: Boolean = false): String? {
            val raw = ref?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val key = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                // 存量完整 URL：只有指向我们自己的桶才有 key 可言，外链原样返回
                runCatching { ownOssObjectKey(URI(raw).toURL()) }.getOrNull() ?: return raw
            } else {
                raw.removePrefix("/").substringBefore("?")
            }
            if (key.isBlank()) return raw
            val dim = size?.takeIf { it > 0 }
            val ttl = if (immutable) IMMUTABLE_TTL_MILLIS else DEFAULT_TTL_MILLIS
            return runCatching { signWithResize(key, dim, dim, ttl) }
                .onFailure { log.warn("[signAsset] 签名失败, 回退原值: ref={}, err={}", raw, it.message) }
                .getOrDefault(raw)
        }


        /**
         * 若 [parsedUrl] 指向本服务自己的 OSS（自定义域名或默认 OSS 域名），返回其对象 key；否则返回 null。
         * 命中时可直接用 SDK 读取，绕开自定义域名公网 HTTPS 的证书校验问题。
         */
        fun ownOssObjectKey(parsedUrl: java.net.URL): String? {
            val host = parsedUrl.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val ownHosts = listOfNotNull(customDomain.takeIf { it.isNotBlank() }, domain)
                .mapNotNull { runCatching { URI(it).host?.lowercase() }.getOrNull() }
            if (host !in ownHosts) return null
            return parsedUrl.path.removePrefix("/").substringBefore("?").takeIf { it.isNotBlank() }
        }

        private fun upload(inputStream: InputStream, path: String) =
            runCatching { ossClient.putObject(bucket, path, inputStream) }
                .getOrElse { throw CommonException(ErrorType.E224, it.message) }
                .also { log.debug("[upload] OSS存储成功: bucket={}, path={}", bucket, path) }

        /**
         * 列出某前缀下的**全部** object key 及其字节数。
         *
         * 这是对账任务（`FILE-SYSTEM-REFACTOR.md` P2）唯一的桶侧数据源，也是整套治理方案里
         * 不可替代的一环：账本与桶之间没有事务 —— scrapper 先 PUT 再由 API 落行，中间任何一步
         * 失败都会在桶里留下一个**库里无人知晓**的对象。这类孤儿只能从桶这一侧发现，
         * 任何纯数据库的手段都看不见它们。
         *
         * 自动翻页到底（每页 1000 条），返回 key → 字节数。
         *
         * @param maxKeys 安全阀：扫到这么多对象就停下并记警告。桶失控时不至于把整个列表读进堆里
         */
        fun listAllObjects(prefix: String, maxKeys: Int = 200_000): Map<String, Long> {
            val result = LinkedHashMap<String, Long>()
            var token: String? = null
            do {
                val request = ListObjectsV2Request(bucket).apply {
                    this.prefix = prefix
                    this.maxKeys = 1000
                    this.continuationToken = token
                }
                val page = ossClient.listObjectsV2(request)
                page.objectSummaries.forEach { result[it.key] = it.size }
                token = page.nextContinuationToken
                if (result.size >= maxKeys) {
                    log.warn("[listAllObjects] 达到安全上限，提前结束: prefix={}, scanned={}", prefix, result.size)
                    return result
                }
            } while (page.isTruncated)
            log.debug("[listAllObjects] prefix={}, objects={}", prefix, result.size)
            return result
        }

        /**
         * 删除OSS对象（用于替换旧文件，如头像/背景图重新上传后清理旧文件）
         * 失败仅记录警告，不抛异常：不应因清理旧文件失败而影响主流程（新文件已上传成功）
         */
        fun delete(path: String) {
            runCatching { ossClient.deleteObject(bucket, path) }
                .onFailure { log.warn("[delete] OSS对象删除失败: bucket={}, path={}, error={}", bucket, path, it.message) }
        }

        /**
         * 生成带缩放样式的限时访问链接
         *
         * 过期时间**向下对齐到窗口边界**：直接用 `now + TTL` 会让同一个对象每次请求都得到不同的
         * URL（query 里的 Expires/Signature 都变），浏览器与 CDN 因此永远 cache miss —— 同一个
         * 图标每刷新一次就回源一次，而每次回源都会触发一次按次计费的 OSS 图片处理。对齐之后，
         * 同一窗口内重复调用返回**逐字节相同**的 URL，缓存才谈得上命中。
         *
         * 代价是实际有效期在 `[TTL/2, TTL]` 之间浮动（取决于落在窗口的哪个位置），所以窗口
         * 取一半 TTL、再补满一个 TTL，保证任何时刻签出的链接至少还有 [expirationMillis] 可用。
         *
         * @param objectName OSS对象名（不含域名）
         * @param width 目标宽度（null/<=0 表示不限制）
         * @param height 目标高度（null/<=0 表示不限制）
         * @param expirationMillis 最短剩余有效期（毫秒）
         */
        fun signWithResize(
            objectName: String,
            width: Int? = null,
            height: Int? = null,
            expirationMillis: Long = DEFAULT_TTL_MILLIS
        ): String {
            log.debug("[signWithResize] objectName={}, width={}, height={}, expirationMillis={}", objectName, width, height, expirationMillis)
            return try {
                val window = (expirationMillis / 2).coerceAtLeast(1)
                val alignedNow = System.currentTimeMillis() / window * window
                val expiration = java.util.Date(alignedNow + window + expirationMillis)
                val request = GeneratePresignedUrlRequest(bucket, objectName).apply {
                    this.expiration = expiration
                    // 阿里云 OSS 图片处理(IMG)不支持 SVG 缩放: 带 image/resize 会返回
                    // "This image format is not supported" (EC 0040-00000005)。SVG 跳过缩放, 原图直出,
                    // 由前端用 CSS 控制展示尺寸 (DiceBear 头像即为 SVG)。
                    val isSvg = objectName.substringAfterLast('.', "").equals("svg", ignoreCase = true)
                    val hasWidth = width?.let { it > 0 } == true
                    val hasHeight = height?.let { it > 0 } == true
                    if (!isSvg && (hasWidth || hasHeight)) {
                        // 使用 m_fill 以填充方式裁剪，确保输出尺寸精确匹配期望的宽高
                        val style = StringBuilder("image/resize,m_fill")
                        width?.takeIf { it > 0 }?.let { style.append(",w_$it") }
                        height?.takeIf { it > 0 }?.let { style.append(",h_$it") }
                        this.process = style.toString()
                        log.debug("[signWithResize] 添加缩放样式: process={}", style)
                    }
                }

                val url = ossClient.generatePresignedUrl(request)
                val query = url.query
                if (customDomain.isNotBlank()) {
                    buildString {
                        append(customDomain)
                        append(url.path)
                        if (!query.isNullOrBlank()) append("?").append(query)
                    }.also { log.debug("[signWithResize] 使用自定义域名生成URL: {}", it) }
                } else {
                    url.toString().also { log.debug("[signWithResize] 使用OSS默认域名生成URL: {}", it) }
                }
            } catch (e: Exception) {
                throw CommonException(ErrorType.E221, e.message)
            }
        }


        /**
         * 根据对象路径生成带缩放参数的限时访问链接
         *
         * @param path OSS对象路径或完整URL
         * @param width 目标宽度（<=0则不限定）
         * @param height 目标高度（<=0则不限定）
         */
        fun resizeAndSignImg(path: String, width: Int, height: Int): String {
            log.debug("[resizeAndSignImg] path={}, width={}, height={}", path, width, height)
            val objectName = runCatching { URI(path).path.removePrefix("/") }
                .getOrElse { path.removePrefix("/") }
                .substringBefore("?")
                .takeIf { it.isNotBlank() } ?: throw CommonException(ErrorType.E223, "path:$path")
            log.debug("[resizeAndSignImg] 解析objectName={}", objectName)
            return signWithResize(objectName, width.takeIf { it > 0 }, height.takeIf { it > 0 })
        }

        /**
         * 上传用户文件（头像/背景图）到OSS，包含类型和大小校验
         *
         * @param file 上传的文件
         * @param fileType 文件类型（含大小限制和目标路径）
         * @return Pair(currentName: UUID文件名, ext: 后缀)，由调用方拼成 object key 并记入账本
         */
        fun uploadUserFile(file: MultipartFile, fileType: FileType): Pair<String, String> {
            log.debug("[uploadUserFile] fileType={}, fileName={}, contentType={}, size={}", fileType, file.originalFilename, file.contentType, file.size)
            file.contentType?.startsWith(fileType.type)
                ?.let { if (!it) throw CommonException(ErrorType.E103) }
            if (file.size > fileType.limit) throw CommonException(ErrorType.E104)

            val ext = FileUtil.extName(file.originalFilename ?: throw CommonException(ErrorType.E227))
                .replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
                .also {
                    log.debug("[uploadUserFile] 文件后缀: ext={}", it)
                    if (it.isEmpty() || it.length > 10) throw CommonException(ErrorType.E226)
                }

            val uuid = IdUtil.fastUUID()
            val path = "${fileType.folder}/$uuid.$ext"
            log.debug("[uploadUserFile] 开始上传: path={}", path)
            upload(file.inputStream, path)
            log.debug("[uploadUserFile] 上传成功: uuid={}, ext={}", uuid, ext)
            return Pair(uuid, ext)
        }

        fun defaultImgBacById(linkId: String): String {
            log.debug("[defaultImgBacById] linkId={}", linkId)
            return buildString {
                append(FileType.BACKGROUND.folder)
                append("/")
                append(linkId)
                append(".png")
            }.let { signWithResize(it) }
        }
    }
}
