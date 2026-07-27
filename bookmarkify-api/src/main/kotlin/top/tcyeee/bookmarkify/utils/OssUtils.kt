package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.IdUtil
import com.aliyun.oss.OSS
import org.springframework.web.multipart.MultipartFile
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.model.GeneratePresignedUrlRequest
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.ImgInfo
import top.tcyeee.bookmarkify.entity.dto.LogoResult
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.entity.BookmarkLogoEntity
import top.tcyeee.bookmarkify.entity.enums.FileType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import javax.imageio.ImageIO

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
         * 将线上地址转存到OSS
         * 1.只存储尺寸最大的那个
         * @param list 包含LOGO和OG的List
         * @param bookmarkId 书签ID(用于添加文件夹)
         * @return 返回最大的LOGO信息
         */
        fun restoreBookmarkLogoAndOg(list: List<ManifestIcon>?, bookmarkId: String): LogoResult {
            log.debug("[restoreBookmarkLogoAndOg] bookmarkId={}, iconCount={}", bookmarkId, list?.size)
            if (list.isNullOrEmpty()) throw CommonException(ErrorType.E999)

            // 存储OG（og:image 宽屏分享图，与 LOGO 是两类图）：仅上传 OSS，地址不落库
            val ogs = list.filter { it.isOg() }.filterNot { it.src.isNullOrBlank() }
            log.debug("[restoreBookmarkLogoAndOg] 找到OG图片数={}", ogs.size)
            if (ogs.isNotEmpty()) runCatching {
                log.debug("[restoreBookmarkLogoAndOg] 开始存储OG: src={}", ogs.first().src)
                restoreImg(FileType.WEBSITE_OG, ogs.first().src!!, bookmarkId)
            }.onFailure { log.warn("[restoreBookmarkLogoAndOg] OG存储失败: {}", it.message) }

            // 找到最大的那个LOGO
            val maximalIcon: ManifestIcon = list
                .filterNot { it.isOg() }
                .filterNot { it.src.isNullOrBlank() }
                .filterNot { it.src!!.endsWith(".ico") }
                .maxByOrNull { it.size() } ?: run {
                log.debug("[restoreBookmarkLogoAndOg] 未找到合适的LOGO图标, 返回空结果")
                return LogoResult(logo = null, logoUrl = null)
            }
            log.debug("[restoreBookmarkLogoAndOg] 选中最大LOGO: src={}, size={}", maximalIcon.src, maximalIcon.size())

            // 存储高清 LOGO 并捕获其 OSS 永久地址
            return runCatching { restoreImg(FileType.WEBSITE_LOGO, maximalIcon.src!!, bookmarkId) }
                .getOrElse { throw CommonException(ErrorType.E218, it.message) }
                .let { logoInfo ->
                    log.debug("[restoreBookmarkLogoAndOg] LOGO存储成功: url={}, width={}, height={}", logoInfo.url, logoInfo.width, logoInfo.height)
                    LogoResult(
                        logo = BookmarkLogoEntity(
                            bookmarkId = bookmarkId,
                            size = logoInfo.size,
                            width = logoInfo.width,
                            height = logoInfo.height,
                            suffix = FileUtil.extName(logoInfo.url) ?: "png",
                        ),
                        logoUrl = logoInfo.url
                    )
                }
        }

        /**
         * 文件存储 当前只编写了图片文件
         * @param fileType 文件类型
         * @param url 文件线上地址
         * @param bookmarkId 书签ID(用于添加文件夹)
         */
        fun restoreImg(fileType: FileType, url: String, bookmarkId: String): ImgInfo {
            log.debug("[restoreImg] 开始拉取远程图片: fileType={}, url={}, bookmarkId={}", fileType, url, bookmarkId)
            val parsedUrl = runCatching { URI.create(url).toURL() }
                .getOrElse { throw CommonException(ErrorType.E223, it.message) }

            // 来源本就是本服务自己的 OSS（scrapper 与 API 共用同一桶，logo 形如 cdn.bookmarkify.cc/...）：
            // 直接用 OSS SDK 读取对象，绕开自定义域名公网 HTTPS 可能的 TLS 证书 SAN 不匹配
            // （No subject alternative DNS name matching cdn.bookmarkify.cc found），同时免去一次外网往返。
            ownOssObjectKey(parsedUrl)?.let { key ->
                log.debug("[restoreImg] 源自本服务 OSS，改用 SDK 直读: key={}", key)
                val obj = runCatching { ossClient.getObject(bucket, key) }
                    .getOrElse { throw CommonException(ErrorType.E218, it.message) }
                return obj.objectContent.use { uploadImg(it, fileType, url, bookmarkId) }
            }

            // SSRF 防护：仅允许 http/https，且解析后的 IP 不能是回环/链路本地/任意地址/RFC1918 内网
            val scheme = parsedUrl.protocol?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw CommonException(ErrorType.E223, "scheme:$scheme")
            }
            val host = parsedUrl.host?.takeIf { it.isNotBlank() }
                ?: throw CommonException(ErrorType.E223, "host:empty")
            val addr = runCatching { java.net.InetAddress.getByName(host) }
                .getOrElse { throw CommonException(ErrorType.E223, "dns:$host") }
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress
                || addr.isLinkLocalAddress || addr.isSiteLocalAddress
            ) {
                throw CommonException(ErrorType.E223, "private:$host")
            }

            // F-05 (DNS rebinding): pin the connection to the already-validated IP so the JVM
            // cannot re-resolve the hostname and get a different (private) address on the second lookup.
            // HTTPS 例外: TLS 证书是按 URL 主机名校验的, 换成 IP 字面量会导致 SAN 不匹配而握手失败
            // (No subject alternative names matching IP address)。HTTPS 因此改用原主机名直连——
            // TLS 本身要求证书匹配该公网域名, rebinding 到内网主机会因拿不出合法证书而握手失败, 已足够防护。
            val pinByIp = scheme == "http"
            val safeUrl = if (pinByIp) {
                URI(parsedUrl.protocol, null, addr.hostAddress, parsedUrl.port, parsedUrl.file, null, null).toURL()
            } else {
                parsedUrl
            }
            val connection = runCatching { safeUrl.openConnection() }
                .getOrElse { throw CommonException(ErrorType.E223, it.message) }
                .apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    // F-01 (SSRF redirect): disable automatic redirect following.
                    // Without this a 301/302 from the validated public server could redirect
                    // to a private/internal address, bypassing the SSRF guard entirely.
                    (this as? java.net.HttpURLConnection)?.instanceFollowRedirects = false
                    // 仅在以 IP 字面量连接 (HTTP) 时回填 Host 头, 让虚拟主机正确解析;
                    // HTTPS 直接用主机名连接, 无需覆盖 Host。
                    if (pinByIp) setRequestProperty("Host", host)
                }

            // 限制文件大小
            val length = connection.contentLengthLong
            log.debug("[restoreImg] 远程文件大小: length={} bytes, limit={} bytes", length, fileType.limit)
            if (length != -1L && length > fileType.limit) throw CommonException(ErrorType.E219, "length:${length}")

            return connection.getInputStream().use {
                log.debug("[restoreImg] 开始上传图片到OSS")
                this.uploadImg(it, fileType, url, bookmarkId)
            }
        }

        /**
         * 若 [parsedUrl] 指向本服务自己的 OSS（自定义域名或默认 OSS 域名），返回其对象 key；否则返回 null。
         * 命中时可直接用 SDK 读取，绕开自定义域名公网 HTTPS 的证书校验问题。
         */
        private fun ownOssObjectKey(parsedUrl: java.net.URL): String? {
            val host = parsedUrl.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            val ownHosts = listOfNotNull(customDomain.takeIf { it.isNotBlank() }, domain)
                .mapNotNull { runCatching { URI(it).host?.lowercase() }.getOrNull() }
            if (host !in ownHosts) return null
            return parsedUrl.path.removePrefix("/").substringBefore("?").takeIf { it.isNotBlank() }
        }


        /**
         * @param inputStream 文件流
         * @param fileType 文件类型(用于确定文件夹)
         * @param url 文件线上地址
         */
        fun uploadImg(inputStream: InputStream, fileType: FileType, url: String, bookmarkId: String): ImgInfo {
            log.debug("[uploadImg] fileType={}, url={}, bookmarkId={}", fileType, url, bookmarkId)
            if (!fileType.isImg()) throw CommonException(ErrorType.E999)
            // F-OOM: 远端可能不返回 Content-Length（绕过 restoreImg 的预检查），因此这里必须有界读取，
            // 绝不能用 readBytes() 把整个流一次性吞进堆内存——否则恶意/异常服务器可触发 OOM。
            val bytes = inputStream.readBounded(fileType.limit)
                .also { log.debug("[uploadImg] 读取字节数={}, limit={}", it.size, fileType.limit) }

            // 则检查图片的长和宽(后续用于重命名)
            val img: Pair<Int, Int> = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }
                .getOrElse { throw CommonException(ErrorType.E220, it.message) }
                .let { Pair(it.width, it.height) }
            log.debug("[uploadImg] 图片尺寸: width={}, height={}", img.first, img.second)

            return buildString {
                append(fileType.folder)
                append("/")
                append(bookmarkId)
                append("/")
                append(if (img.first == img.second) img.first else "OG")
                append(".")
                append(FileUtil.extName(url)?.substringBefore("?") ?: throw CommonException(ErrorType.E225))
            }
                // 重新构造输入流，避免前面的 readBytes 导致流已被读完
                .also {
                    log.debug("[uploadImg] OSS存储路径: {}", it)
                    this.upload(ByteArrayInputStream(bytes), it)
                }
                .let { ImgInfo("$domain/$it", bytes.size.toLong(), img.first, img.second) }
                .also { log.debug("[uploadImg] 上传完成: url={}, size={}", it.url, it.size) }
        }

        /**
         * @param inputStream 文件流
         * @param path 文件的最终存储地址(包含名称和后缀) eg /logo/dkgy-hfauw-ekadfa/og.png
         * return 最终线上地址
         */
        /**
         * 有界读取：最多读取 [limit] 字节，超出立即抛出，避免无 Content-Length 时的 OOM。
         */
        private fun InputStream.readBounded(limit: Int): ByteArray {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val read = this.read(chunk)
                if (read == -1) break
                total += read
                if (total > limit) throw CommonException(ErrorType.E219, "length>$limit")
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }

        private fun upload(inputStream: InputStream, path: String) =
            runCatching { ossClient.putObject(bucket, path, inputStream) }
                .getOrElse { throw CommonException(ErrorType.E224, it.message) }
                .also { log.debug("[upload] OSS存储成功: bucket={}, path={}", bucket, path) }

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
         * @param objectName OSS对象名（不含域名）
         * @param width 目标宽度（null/<=0 表示不限制）
         * @param height 目标高度（null/<=0 表示不限制）
         * @param expirationMillis 过期时间（毫秒）
         */
        fun signWithResize(
            objectName: String,
            width: Int? = null,
            height: Int? = null,
            expirationMillis: Long = 3600 * 1000
        ): String {
            log.debug("[signWithResize] objectName={}, width={}, height={}, expirationMillis={}", objectName, width, height, expirationMillis)
            return try {
                val expiration = java.util.Date(System.currentTimeMillis() + expirationMillis)
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
         * 获取带缩放参数的私有图片签名URL
         *
         * @param bookmarkId 书签地址
         * @param maxmalSize 最大尺寸(文件名称)
         * @param size 格式化后的宽&高
         * @param expirationMillis 过期时间（毫秒），默认1小时
         * @return 签名URL
         */
        fun getLogoUrl(
            bookmarkId: String, maxmalSize: Int, size: Int, expirationMillis: Long = 3600 * 1000
        ): String {
            log.debug("[getLogoUrl] bookmarkId={}, maxmalSize={}, size={}, expirationMillis={}", bookmarkId, maxmalSize, size, expirationMillis)
            val objectName = buildString {
                append(FileType.WEBSITE_LOGO.folder)
                append("/")
                append(bookmarkId)
                append("/")
                append(maxmalSize)
                append(".png")
            }
            val target = maxmalSize.coerceAtMost(size)
            log.debug("[getLogoUrl] objectName={}, target={}x{}", objectName, target, target)
            return signWithResize(objectName, target, target, expirationMillis)
        }

        /**
         * 上传用户文件（头像/背景图）到OSS，包含类型和大小校验
         *
         * @param file 上传的文件
         * @param fileType 文件类型（含大小限制和目标路径）
         * @return Pair(currentName: UUID文件名, ext: 后缀)，用于构建 UserFile
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
