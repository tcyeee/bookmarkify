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
enum class FileType(val limit: Int, val prefix: String, val exfix: String, val folder: String) {
    AVATAR(5 * 1024 * 1024, "image/", "jpg", "avatar"),
}

fun uploadAvatar(file: MultipartFile, uid: String, fileBasePath: String): String {
    val fileType = FileType.AVATAR

    // 验证文件类型
    file.contentType?.startsWith(fileType.prefix)?.let { if (!it) throw CommonException(ErrorType.E103) }

    // 验证文件大小（限制为 5MB）
    if (file.size > fileType.limit) throw CommonException(ErrorType.E104)

    val dest = getfileName(file, fileType, uid, fileBasePath)

    saveFile(file, dest);

    return dest.name
}

// 生成文件名：avatar/{uid}/{uuid}.{ext}
private fun getfileName(file: MultipartFile, fileType: FileType, uid: String, fileBasePath: String): File {
    val ext = FileUtil.extName(file.originalFilename ?: fileType.exfix)
    val fileName = "${fileType.folder}/$uid/${IdUtil.fastUUID()}.$ext"
    val dest = File(fileBasePath, fileName)

    // 确保目录存在
    FileUtil.mkdir(dest.parentFile)
    return dest
}

// 保存文件
private fun saveFile(file: MultipartFile, dest: File) {
    file.transferTo(dest)
}