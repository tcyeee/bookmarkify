package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.entity.UserFile
import top.tcyeee.bookmarkify.entity.entity.UserFileType
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.server.IFileService
import top.tcyeee.bookmarkify.utils.FileType
import top.tcyeee.bookmarkify.utils.uploadFile

/**
 * 文件记录 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:05
 */
@Service
class FileServiceImpl(
    private val projectConfig: ProjectConfig,
) : IFileService, ServiceImpl<FileMapper, UserFile>() {

    override fun updateAvatar(uid: String, file: MultipartFile): UserFile {
        val fileName = uploadFile(file, projectConfig.imgPath, FileType.AVATAR)
        return UserFile(
            uid = uid,
            originName = file.originalFilename ?: "",
            type = UserFileType.AVATAR_IMAGE,
            currentName = fileName,
            size = file.size,
        ).also { save(it) }
    }

    override fun uploadBackground(uid: String, file: MultipartFile): UserFile {
        val fileName = uploadFile(file, projectConfig.imgPath, FileType.BACKGROUND)
        return UserFile(
            uid = uid,
            originName = file.originalFilename ?: "",
            type = UserFileType.BACKGROUND_IMAGE,
            currentName = fileName,
            size = file.size,
        ).also { save(it) }
    }
}
