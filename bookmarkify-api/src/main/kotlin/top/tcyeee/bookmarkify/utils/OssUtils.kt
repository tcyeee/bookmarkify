package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import com.aliyun.oss.OSS
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

    @Value("\${aliyun.oss.endpoint}")
    private lateinit var endpoint: String

    @Value("\${aliyun.oss.access-key-id}")
    private lateinit var accessKeyId: String

    @Value("\${aliyun.oss.access-key-secret}")
    private lateinit var accessKeySecret: String

    @Value("\${aliyun.oss.bucket-name}")
    private lateinit var bucketName: String

    @Value("\${aliyun.oss.domain-name}")
    private lateinit var domainName: String

    @PostConstruct
    fun init() {
        ossClient = OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret)
        bucket = bucketName
        initDomain(endpoint, domainName, bucketName)
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

        /**
         * 将线上地址转存到OSS
         * 1.只存储尺寸最大的那个
         * @param list 包含LOGO和OG的List
         * @param bookmarkId 书签ID(用于添加文件夹)
         * @return 返回最大的LOGO信息
         */
        fun restoreWebsiteLogoAndOg(list: List<ManifestIcon>?, bookmarkId: String): WebsiteLogoEntity {
            if (list.isNullOrEmpty()) throw CommonException(ErrorType.E999)

            // 存储OG
            val ogs = list.filter { it.isOg() }.filterNot { it.src.isNullOrBlank() }
            if (ogs.isNotEmpty()) runCatching { restoreImg(FileType.WEBSITE_OG, ogs.first().src!!, bookmarkId) }

            // 找到最大的那个LOGO
            val maxmalIcon: ManifestIcon = list.filterNot { it.isOg() }
                .filterNot { it.src.isNullOrBlank() }
                .filterNot { it.src!!.endsWith(".ico") }
                .maxBy { it.size() }

            // 存储LOGO并返回
            return runCatching { restoreImg(FileType.WEBSITE_LOGO, maxmalIcon.src!!, bookmarkId) }
                .getOrElse { throw CommonException(ErrorType.E218, it.message) }
                .let { logoInfo ->
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
            val connection = runCatching { URI.create(url).toURL().openConnection() }
                .getOrElse { throw CommonException(ErrorType.E223, it.message) }
                .apply { connectTimeout = 5000; readTimeout = 5000 }

            // 限制文件大小
            val length = connection.contentLengthLong
            if (length != -1L && length > fileType.limit) throw CommonException(ErrorType.E219, "length:${length}")

            return connection.getInputStream().use {
                this.uploadImg(it, fileType, url, bookmarkId)
            }
        }


        /**
         * @param inputStream 文件流
         * @param fileType 文件类型(用于确定文件夹)
         * @param url 文件线上地址
         */
        fun uploadImg(inputStream: InputStream, fileType: FileType, url: String, bookmarkId: String): ImgInfo {
            if (!fileType.isImg()) throw CommonException(ErrorType.E999)
            val bytes = inputStream.readBytes()
                .also { if (it.size > fileType.limit) throw CommonException(ErrorType.E219) }

            // 则检查图片的长和宽(后续用于重命名)
            val img: Pair<Int, Int> = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }
                .getOrElse { throw CommonException(ErrorType.E220, it.message) }
                .let { Pair(it.width, it.height) }

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
                .also { this.upload(ByteArrayInputStream(bytes), it) }
                .let { ImgInfo("$domain/$it", bytes.size.toLong(), img.first, img.second) }
        }

        /**
         * @param inputStream 文件流
         * @param path 文件的最终存储地址(包含名称和后缀) eg /logo/dkgy-hfauw-ekadfa/og.png
         * return 最终线上地址
         */
        private fun upload(inputStream: InputStream, path: String) =
            runCatching { ossClient.putObject(bucket, path, inputStream) }
                .getOrElse { throw CommonException(ErrorType.E224, it.message) }
                .also { log.info("[DEBUG] OSS存储成功! Bucket:$bucket; FileName:$path") }

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
            val objectName = buildString {
                append(FileType.WEBSITE_LOGO.folder)
                append("/")
                append(bookmarkId)
                append("/")
                append(maxmalSize)
                append(".png")
            }
            return try {
                val expiration = java.util.Date(System.currentTimeMillis() + expirationMillis)
                val request = GeneratePresignedUrlRequest(bucket, objectName)
                request.expiration = expiration

                val style = StringBuilder("image/resize,m_lfit")
                style.append(",w_${maxmalSize.coerceAtMost(size)}")
                style.append(",h_${maxmalSize.coerceAtMost(size)}")
                request.process = style.toString()

                val url = ossClient.generatePresignedUrl(request)
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
