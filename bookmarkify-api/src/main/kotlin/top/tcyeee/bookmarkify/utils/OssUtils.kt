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
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.entity.WebsiteLogoEntity
import top.tcyeee.bookmarkify.entity.enums.FileType
import java.io.ByteArrayInputStream
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
        fun restoreWebsiteLogoAndOg(list: List<ManifestIcon>?, bookmarkId: String): WebsiteLogoEntity? {
            log.debug("[restoreWebsiteLogoAndOg] bookmarkId={}, iconCount={}", bookmarkId, list?.size)
            if (list.isNullOrEmpty()) throw CommonException(ErrorType.E999)

            // 存储OG
            val ogs = list.filter { it.isOg() }.filterNot { it.src.isNullOrBlank() }
            log.debug("[restoreWebsiteLogoAndOg] 找到OG图片数={}", ogs.size)
            if (ogs.isNotEmpty()) runCatching {
                log.debug("[restoreWebsiteLogoAndOg] 开始存储OG: src={}", ogs.first().src)
                restoreImg(FileType.WEBSITE_OG, ogs.first().src!!, bookmarkId)
            }.onFailure { log.warn("[restoreWebsiteLogoAndOg] OG存储失败: {}", it.message) }

            // 找到最大的那个LOGO
            val maximalIcon: ManifestIcon = list
                .filterNot { it.isOg() }
                .filterNot { it.src.isNullOrBlank() }
                .filterNot { it.src!!.endsWith(".ico") }
                .maxByOrNull { it.size() } ?: run {
                log.debug("[restoreWebsiteLogoAndOg] 未找到合适的LOGO图标, 返回null")
                return null
            }
            log.debug("[restoreWebsiteLogoAndOg] 选中最大LOGO: src={}, size={}", maximalIcon.src, maximalIcon.size())

            // 存储LOGO并返回
            return runCatching { restoreImg(FileType.WEBSITE_LOGO, maximalIcon.src!!, bookmarkId) }
                .getOrElse { throw CommonException(ErrorType.E218, it.message) }
                .let { logoInfo ->
                    log.debug("[restoreWebsiteLogoAndOg] LOGO存储成功: url={}, width={}, height={}", logoInfo.url, logoInfo.width, logoInfo.height)
                    WebsiteLogoEntity(
                        bookmarkId = bookmarkId,
                        size = logoInfo.size,
                        width = logoInfo.width,
                        height = logoInfo.width,
                        suffix = FileUtil.extName(logoInfo.url) ?: "png",
                        isOgImg = false
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
            val connection = runCatching { URI.create(url).toURL().openConnection() }
                .getOrElse { throw CommonException(ErrorType.E223, it.message) }
                .apply { connectTimeout = 5000; readTimeout = 5000 }

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
         * @param inputStream 文件流
         * @param fileType 文件类型(用于确定文件夹)
         * @param url 文件线上地址
         */
        fun uploadImg(inputStream: InputStream, fileType: FileType, url: String, bookmarkId: String): ImgInfo {
            log.debug("[uploadImg] fileType={}, url={}, bookmarkId={}", fileType, url, bookmarkId)
            if (!fileType.isImg()) throw CommonException(ErrorType.E999)
            val bytes = inputStream.readBytes()
                .also {
                    log.debug("[uploadImg] 读取字节数={}, limit={}", it.size, fileType.limit)
                    if (it.size > fileType.limit) throw CommonException(ErrorType.E219)
                }

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
        private fun upload(inputStream: InputStream, path: String) =
            runCatching { ossClient.putObject(bucket, path, inputStream) }
                .getOrElse { throw CommonException(ErrorType.E224, it.message) }
                .also { log.debug("[upload] OSS存储成功: bucket={}, path={}", bucket, path) }

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
                    val hasWidth = width?.let { it > 0 } == true
                    val hasHeight = height?.let { it > 0 } == true
                    if (hasWidth || hasHeight) {
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
