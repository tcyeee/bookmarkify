package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.entity.BackgroundType
import top.tcyeee.bookmarkify.entity.entity.ImageBackgroundEntity
import top.tcyeee.bookmarkify.entity.entity.UserBackgroundLinkEntity
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.entity.UserFile
import top.tcyeee.bookmarkify.entity.entity.UserFileType
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.server.IFileService
import top.tcyeee.bookmarkify.server.IImageBackgroundService
import top.tcyeee.bookmarkify.utils.FileType
import top.tcyeee.bookmarkify.utils.uploadAvatar
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
    private val imageBackground: IImageBackgroundService,
    private val userBackgroundLinkService: UserBackgroundLinkServiceImpl,
    private val userService: UserServiceImpl,
) : IFileService, ServiceImpl<FileMapper, UserFile>() {

    override fun updateAvatar(uid: String, file: MultipartFile): String {
        val fileName = uploadAvatar(file, projectConfig.imgPath)

        val userFileEntity = UserFile(
            uid = uid,
            originName = file.originalFilename ?: "",
            type = UserFileType.AVATAR_IMAGE,
            currentName = fileName,
            size = file.size,
        ).also { save(it) }

        userService.ktUpdate()
            .eq(UserEntity::id, uid)
            .set(UserEntity::avatarFileId, userFileEntity.id)
            .update()

        return fileName
    }

    override fun uploadBackground(uid: String, file: MultipartFile): String {
        val fileName = uploadFile(file, projectConfig.imgPath, FileType.BACKGROUND)

        val userFileEntity = UserFile(
            uid = uid,
            originName = file.originalFilename ?: "",
            type = UserFileType.AVATAR_IMAGE,
            currentName = fileName,
            size = file.size,
        ).also { save(it) }

        // 添加到背景图片数据库
        val imageBackgroundEntity = ImageBackgroundEntity(uid = uid, fileId = userFileEntity.id)
            .also { imageBackground.save(it) }

        // 修改用户背景图片设置
        UserBackgroundLinkEntity(
            uid = uid,
            type = BackgroundType.IMAGE,
            backgroundLinkId = imageBackgroundEntity.id,
        ).also { userBackgroundLinkService.save(it) }

        // 返回相对路径
        return fileName
    }
}
