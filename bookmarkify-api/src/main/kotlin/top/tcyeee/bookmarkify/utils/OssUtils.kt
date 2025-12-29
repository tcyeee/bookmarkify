package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import com.aliyun.oss.OSS
import com.aliyun.oss.OSSClientBuilder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.io.ByteArrayInputStream
import java.net.URI
import javax.imageio.ImageIO
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.entity.WebsiteLogoEntity
import top.tcyeee.bookmarkify.entity.enums.FileType

/**
 * @author tcyeee
 * @date 12/29/25 13:43
 */
@Component
class OssUtils {

    @Value("\${aliyun.oss.endpoint}")
    private lateinit var endpoint: String

    @Value("\${aliyun.oss.access-key-id}")
    private lateinit var accessKeyId: String

    @Value("\${aliyun.oss.access-key-secret}")
    private lateinit var accessKeySecret: String

    @Value("\${aliyun.oss.bucket-name}")
    private lateinit var bucketName: String

    @Value("\${aliyun.oss.domain:}")
    private lateinit var domainConfig: String

    @PostConstruct
    fun init() {
        ossClient = OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret)
        bucket = bucketName
        initDomain(endpoint, domainConfig, bucketName)
    }

    @PreDestroy
    fun destroy() {
        ossClient.shutdown()
    }

    companion object {
        private val log = LoggerFactory.getLogger(OssUtils::class.java)
        private lateinit var ossClient: OSS
        private lateinit var bucket: String
        private lateinit var domain: String
        private lateinit var customDomain: String

        fun initDomain(endpoint: String, domainConfig: String, bucketName: String) {
            val protocol = if (endpoint.startsWith("https://")) "https://" else "http://"
            if (domainConfig.isNotBlank()) {
                val d = if (domainConfig.startsWith("http")) domainConfig
                else "$protocol$domainConfig"
                domain = d.removeSuffix("/")
                customDomain = domain
            } else {
                val rawEndpoint = endpoint.removePrefix(protocol)
                domain = "$protocol$bucketName.$rawEndpoint"
                customDomain = ""
            }
        }

        fun restoreWebsiteLogo(
            list: List<ManifestIcon>?, bookmarkId: String
        ): List<WebsiteLogoEntity> {
            val result = mutableListOf<WebsiteLogoEntity>()
            if (list.isNullOrEmpty()) return result

            list.forEach { icon ->
                if (icon.src.isNullOrBlank()) return@forEach
                val customPath = "${FileType.WEBSITE_LOGO.folder}/$bookmarkId/${icon.size()}"
                runCatching {
                    restoreLogoImg(icon.src, customPath)
                }.getOrElse { err -> throw CommonException(ErrorType.E218, err.message) }.let { logoInfo ->
                    result.add(
                        WebsiteLogoEntity(
                            bookmarkId = bookmarkId,
                            size = logoInfo.size,
                            width = if (logoInfo.width > 0) logoInfo.width
                            else icon.size(),
                            height = if (logoInfo.height > 0) logoInfo.height
                            else icon.size(),
                            suffix = FileUtil.extName(logoInfo.url) ?: "png",
                            isOgImg = icon.isOg()
                        )
                    )
                }
            }
            return result
        }

        data class LogoInfo(val url: String, val size: Long, val width: Int, val height: Int)

        /**
         * 转存LOGO图片
         *
         * @param url 在线文件地址
         * @param customPath 自定义文件路径（不包含后缀）
         * @return 文件访问 URL 和 文件大小
         */
        fun restoreLogoImg(url: String, customPath: String): LogoInfo {
            val fileType = FileType.WEBSITE_LOGO
            try {
                // 打开网络连接
                val connection = URI.create(url).toURL().openConnection()
                // 设置超时
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                // 检查LOGO大小
                val length = connection.contentLengthLong
                if (length != -1L && length > fileType.limit) throw CommonException(ErrorType.E219, "length:${length}")

                // 获取输入流
                return connection.getInputStream().use { inputStream ->
                    val bytes = inputStream.readBytes()
                    if (bytes.size > fileType.limit) throw CommonException(ErrorType.E219, "length:${bytes.size}")
                    var width = 0
                    var height = 0
                    try {
                        val img = ImageIO.read(ByteArrayInputStream(bytes))
                        if (img != null) {
                            width = img.width
                            height = img.height
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        throw CommonException(ErrorType.E220, e.message)
                    }

                    // 生成文件名
                    var suffix = FileUtil.extName(url)
                    if (suffix.isNullOrBlank()) {
                        val contentType = connection.contentType
                        suffix = if (contentType?.contains("image") == true) "png" else "tmp"
                    }
                    // 去除可能的查询参数
                    if (suffix.contains("?")) suffix = suffix.substringBefore("?")

                    val fileName = if (FileUtil.extName(customPath).equals(suffix, ignoreCase = true)) customPath
                    else "$customPath.$suffix"

                    ossClient.putObject(bucket, fileName, ByteArrayInputStream(bytes))
                    val objectUrl = "$domain/$fileName"
                    log.info("[DEBUG] 网站LOGO存储成功: $objectUrl")
                    LogoInfo(objectUrl, bytes.size.toLong(), width, height)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException("网站LOGO${url}存储OSS失败", e)
            }
        }

        /**
         * 获取私有文件的签名URL
         *
         * @param objectName 文件路径
         * @param expirationMillis 过期时间（毫秒），默认1小时
         * @return 签名URL
         */
        fun getPrivateUrl(objectName: String, expirationMillis: Long = 3600 * 1000): String {
            return try {
                val expiration = java.util.Date(System.currentTimeMillis() + expirationMillis)
                val url = ossClient.generatePresignedUrl(bucket, objectName, expiration)
                if (customDomain.isNotBlank()) {
                    "$customDomain${url.path}?${url.query}"
                } else {
                    url.toString()
                }
            } catch (e: Exception) {
                throw CommonException(ErrorType.E221, e.message)
            }
        }
    }
}
