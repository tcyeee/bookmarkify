package top.tcyeee.bookmarkify.utils

import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.IdUtil
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import java.io.File

/**
 * @author tcyeee
 * @date 12/7/25 10:08
 */
enum class FileType(val limit: Int, val prefix: String, val suffix: String, val folder: String) {
    AVATAR(5 * 1024 * 1024, "image/", "jpg", "avatar"),
}

fun uploadAvatar(file: MultipartFile, uid: String, fileBasePath: String): String {
    val fileType = FileType.AVATAR

    // 验证文件类型
    file.contentType?.startsWith(fileType.prefix)?.let { if (!it) throw CommonException(ErrorType.E103) }

    // 验证文件大小（限制为 5MB）
    if (file.size > fileType.limit) throw CommonException(ErrorType.E104)

    val (dest, fileName) = getFileName(file, fileType, uid, fileBasePath)

    saveFile(file, dest);

    return fileName
}

// 生成文件名：avatar/{uid}/{uuid}.{ext}
private fun getFileName(file: MultipartFile, fileType: FileType, uid: String, fileBasePath: String): Pair<File, String> {
    // 从原始文件名提取扩展名
    var ext = FileUtil.extName(file.originalFilename ?: fileType.suffix)

    // 安全验证：只允许字母、数字和部分安全字符，移除所有路径分隔符和特殊字符
    ext = ext.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()

    // 如果清理后扩展名为空，使用默认扩展名
    if (ext.isEmpty()) ext = fileType.suffix

    // 验证扩展名长度（防止过长）
    if (ext.length > 10) ext = fileType.suffix

    val fileName = "${fileType.folder}/$uid/${IdUtil.fastUUID()}.$ext"
    val dest = File(fileBasePath, fileName)

    // 确保目录存在
    FileUtil.mkdir(dest.parentFile)
    return Pair(dest, fileName)
}

// 保存文件
private fun saveFile(file: MultipartFile, dest: File) {
    file.transferTo(dest)
}