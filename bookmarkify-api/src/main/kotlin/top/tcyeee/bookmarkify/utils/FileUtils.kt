package top.tcyeee.bookmarkify.utils

import cn.hutool.core.codec.Base64
import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.IdUtil
import cn.hutool.http.HttpUtil
import java.io.File
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.ManifestIcon
import top.tcyeee.bookmarkify.entity.entity.UserFile

object FileUtils {

    // 找到Base64图标
    fun icoBase64(imgs: List<ManifestIcon>?, rawUrl: String): String? {
        val icon = imgs?.firstOrNull { it.sizes == "16x16" || it.src?.endsWith(".ico", ignoreCase = true) == true }
            ?: ManifestIcon(src = "${rawUrl}/favicon.ico")
        return runCatching { HttpUtil.downloadBytes(icon.src)?.let { Base64.encode(it) } }.getOrNull()
    }
}

/** 文件类型 limit: 文件大小限制 prefix: 文件类型前缀 defaultSuffix: 默认文件类型后缀（如果没有读取到后缀，就使用这个拼接） folder: 文件存储目录 */
enum class FileType(val limit: Int, val prefix: String, val folder: String) {
    AVATAR(5 * 1024 * 1024, "image/", "bookmarkify/avatar"),
    BACKGROUND(10 * 1024 * 1024, "image/", "bookmarkify/bac"),
}

/**
 * 上传文件
 * @param file 文件
 * @param fileBasePath 文件存储路径
 * @param fileType 文件类型
 * @return 文件名
 */
fun uploadFile(file: MultipartFile, fileBasePath: String, fileType: FileType, uid: String): UserFile {
    // 验证文件类型
    file.contentType?.startsWith(fileType.prefix)?.let { if (!it) throw CommonException(ErrorType.E103) }
    // 验证文件大小
    if (file.size > fileType.limit) throw CommonException(ErrorType.E104)
    val (dest, fileName) = getFileName(file, fileType, fileBasePath)
    saveFile(file, dest)

    return UserFile(
        uid = uid,
        originName = file.originalFilename ?: throw CommonException(ErrorType.E227),
        type = FileType.AVATAR,
        currentName = fileName,
        size = file.size,
        suffix = file.ext(),
    )
}

/**
 * 生成文件名
 * @param file 文件
 * @param fileType 文件类型
 * @param fileBasePath 文件存储路径
 * @return 文件名
 */
private fun getFileName(file: MultipartFile, fileType: FileType, fileBasePath: String): Pair<File, String> {
    val fileName = "${fileType.folder}/${IdUtil.fastUUID()}.${file.ext()}"
    val dest = File(fileBasePath, fileName)

    // 确保目录存在
    FileUtil.mkdir(dest.parentFile)
    return Pair(dest, fileName)
}

private fun MultipartFile.ext(): String {
    // 从原始文件名提取扩展名
    var ext = FileUtil.extName(this.originalFilename ?: throw CommonException(ErrorType.E226))
    // 安全验证：只允许字母、数字和部分安全字符，移除所有路径分隔符和特殊字符
    ext = ext.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
    // 如果清理后扩展名为空，使用默认扩展名
    if (ext.isEmpty()) throw CommonException(ErrorType.E226)
    // 验证扩展名长度（防止过长）
    if (ext.length > 10) throw CommonException(ErrorType.E226)
    return ext
}

/**
 * 保存文件到本地
 * @param file 文件
 * @param dest 文件存储路径
 */
private fun saveFile(file: MultipartFile, dest: File) {
    file.transferTo(dest)
}