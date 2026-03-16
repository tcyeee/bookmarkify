package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.entity.UserFile
import top.tcyeee.bookmarkify.entity.enums.FileType as OssFileType
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.server.IFileService
import top.tcyeee.bookmarkify.utils.FileType
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 文件记录 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:05
 */
@Service
class FileServiceImpl : IFileService, ServiceImpl<FileMapper, UserFile>() {

    override fun updateAvatar(uid: String, file: MultipartFile): UserFile {
        val (currentName, ext) = OssUtils.uploadUserFile(file, OssFileType.AVATAR)
        return UserFile(uid = uid, originName = file.originalFilename!!, type = FileType.AVATAR,
            currentName = currentName, size = file.size, suffix = ext).also { save(it) }
    }

    override fun uploadBackground(uid: String, file: MultipartFile): UserFile {
        val (currentName, ext) = OssUtils.uploadUserFile(file, OssFileType.BACKGROUND)
        return UserFile(uid = uid, originName = file.originalFilename!!, type = FileType.BACKGROUND,
            currentName = currentName, size = file.size, suffix = ext).also { save(it) }
    }
}
