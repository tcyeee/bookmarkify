package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import com.aliyun.oss.OSS
import com.aliyun.oss.OSSClientBuilder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.net.URI
import java.util.UUID
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

    @PostConstruct
    fun init() {
        ossClient = OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret)
        bucket = bucketName
        // 处理 endpoint 格式，确保存储正确的域名
        val protocol = if (endpoint.startsWith("https://")) "https://" else "http://"
        val rawEndpoint = endpoint.removePrefix(protocol)
        domain = "$protocol$bucketName.$rawEndpoint"
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

        fun restoreWebsiteLogo(list: List<ManifestIcon>?, bookmarkId: String): List<WebsiteLogoEntity> {
            val result = mutableListOf<WebsiteLogoEntity>()
            if (list.isNullOrEmpty()) return result

            list.forEach { icon ->
                if (icon.src.isNullOrBlank()) return@forEach
                val customPath = "${FileType.WEBSITE_LOGO.folder}/$bookmarkId/${icon.size()}"
                runCatching { restoreLogoImg(icon.src, customPath) }
                    .getOrElse { err -> throw CommonException(ErrorType.E218, err.message) }
                    .let { (url, fileSize) ->
                        result.add(
                            WebsiteLogoEntity(
                                bookmarkId = bookmarkId,
                                size = fileSize,
                                width = icon.size(),
                                height = icon.size(),
                                suffix = FileUtil.extName(url) ?: "png",
                                isOgImg = icon.isOg()
                            )
                        )
                    }
            }
            return result
        }

        /**
         * 转存LOGO图片
         *
         * @param url 在线文件地址
         * @param customPath 自定义文件路径（不包含后缀，若为空则自动生成）
         * @return 文件访问 URL 和 文件大小
         */
        fun restoreLogoImg(url: String, customPath: String? = null): Pair<String, Long> {
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
                    // 生成文件名
                    var suffix = FileUtil.extName(url)
                    if (suffix.isNullOrBlank()) {
                        val contentType = connection.contentType
                        suffix = if (contentType?.contains("image") == true) "png" else "tmp"
                    }
                    // 去除可能的查询参数
                    if (suffix.contains("?")) suffix = suffix.substringBefore("?")

                    val fileName =
                        if (!customPath.isNullOrBlank()) {
                            if (FileUtil.extName(customPath).equals(suffix, ignoreCase = true))
                                customPath
                            else "$customPath.$suffix"
                        } else {
                            "${fileType.folder}/${UUID.randomUUID()}.$suffix"
                        }

                    ossClient.putObject(bucket, fileName, inputStream)
                    val objectUrl = "$domain/$fileName"
                    log.info("[DEBUG] 网站LOGO存储成功: $objectUrl")
                    Pair(objectUrl, if (length != -1L) length else 0L)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw RuntimeException("网站LOGO${url}存储OSS失败", e)
            }
        }

        /**
         * 删除文件
         *
         * @param fileName 文件名（包含路径）
         */
        fun delete(fileName: String) {
            try {
                ossClient.deleteObject(bucket, fileName)
                log.info("File deleted successfully: $fileName")
            } catch (e: Exception) {
                log.error("Failed to delete file from OSS", e)
                throw RuntimeException("OSS delete failed", e)
            }
        }
    }
}
