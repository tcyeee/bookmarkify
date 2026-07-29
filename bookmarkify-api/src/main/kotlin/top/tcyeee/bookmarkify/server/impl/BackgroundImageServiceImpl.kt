package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.UserFileVO
import top.tcyeee.bookmarkify.entity.entity.BackgroundImageEntity
import top.tcyeee.bookmarkify.entity.entity.UserFile
import top.tcyeee.bookmarkify.mapper.BackgroundImageMapper
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.server.IBackgroundImageService
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 用户图片背景 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class BackgroundImageServiceImpl(
    private val fileMapper: FileMapper,
    private val projectConfig: ProjectConfig,
) : IBackgroundImageService, ServiceImpl<BackgroundImageMapper, BackgroundImageEntity>() {

    override fun getFileById(id: String): UserFile = getById(id).let { fileMapper.selectById(it.fileId) }

    override fun currentBacImgUrl(uid: String, linkId: String): String =
        baseMapper.selectById(linkId)?.fileId ?: OssUtils.defaultImgBacById(linkId)

    /**
     * 这里的默认图片来自配置文件，需要在这里添加上签名，并且修改尺寸
     * 1.默认的图片已经存在于OSS，名称就是ID
     * 2.返回图片ID，用户选择默认图片的时候，和ID进行关联
     */
    override fun defaultImageBackgrounds(): List<UserFileVO> =
        projectConfig.defaultBackgroundImage.map { UserFile(it).vo() }

    override fun userImageBackgrounds(uid: String): List<UserFileVO> =
        ktQuery().eq(BackgroundImageEntity::uid, uid).eq(BackgroundImageEntity::isDefault, false).list()
            .mapNotNull { fileMapper.selectById(it.fileId).vo() }

    /**
     * 删除用户自传的背景图。
     *
     * 先删 DB 行，成功后再删 OSS 对象与 user_file 行：顺序反过来的话，一旦 DB 删除失败，
     * 库里就会留下一条指向已消失对象的记录。OSS 删除失败只记警告（见 [OssUtils.delete]），
     * 不回滚 —— 用户视角图已经删掉了，残留对象由桶的生命周期规则兜底。
     */
    override fun deleteUserImage(uid: String, id: String): Boolean {
        val fileId = baseMapper.selectById(id)?.takeIf { it.uid == uid && !it.isDefault }?.fileId
        val removed = ktUpdate().eq(BackgroundImageEntity::uid, uid).eq(BackgroundImageEntity::id, id)
            .eq(BackgroundImageEntity::isDefault, false).remove()
        if (removed && fileId != null) {
            fileMapper.selectById(fileId)?.let {
                OssUtils.delete(it.fullPath)
                fileMapper.deleteById(fileId)
            }
        }
        return removed
    }
}
