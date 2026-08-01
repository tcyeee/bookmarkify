package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.UserFileVO
import top.tcyeee.bookmarkify.entity.entity.BackgroundImageEntity
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.mapper.BackgroundImageMapper
import top.tcyeee.bookmarkify.server.IBackgroundImageService
import top.tcyeee.bookmarkify.server.IOssObjectService
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 用户图片背景 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class BackgroundImageServiceImpl(
    private val ossObjectService: IOssObjectService,
    private val projectConfig: ProjectConfig,
) : IBackgroundImageService, ServiceImpl<BackgroundImageMapper, BackgroundImageEntity>() {

    private companion object {
        /** 背景图的展示边长。列表与当前背景共用，单独提出来避免两处写不一样 */
        const val RENDER_SIZE = 256
    }

    override fun getFileById(id: String): OssObjectEntity? =
        getById(id)?.let { ossObjectService.findById(it.fileId) }

    /**
     * 当前背景图的可访问地址。
     *
     * **这里以前返回的是 fileId 字符串**（`selectById(linkId)?.fileId`），而调用方
     * [BackgroundConfigServiceImpl] 把它当 URL 塞进 `fullName` 下发给前端 —— 同一个方法两个
     * 分支两种语义，自定义背景那条分支下发的根本不是地址。现在两条分支统一返回签名地址。
     *
     * 两种 linkId 的来历不同：自定义背景是 `background_image.id`，默认背景是配置里的文件名去掉
     * 后缀（见 [defaultImageBackgrounds]），后者在库里没有任何行，只能按约定拼路径。
     */
    override fun currentBacImgUrl(uid: String, linkId: String): String =
        getFileById(linkId)?.signedUrl(RENDER_SIZE) ?: OssUtils.defaultImgBacById(linkId)

    /**
     * 这里的默认图片来自配置文件，需要在这里添加上签名，并且修改尺寸
     * 1.默认的图片已经存在于OSS，名称就是ID
     * 2.返回图片ID，用户选择默认图片的时候，和ID进行关联
     */
    override fun defaultImageBackgrounds(): List<UserFileVO> =
        projectConfig.defaultBackgroundImage.map { fileName ->
            val id = fileName.substringBefore(".")
            UserFileVO(id = id, fullName = OssUtils.defaultImgBacById(id))
        }

    /**
     * 用户自传的背景图列表。
     *
     * 账本行**一次批量取回**：这里原先是 `list().mapNotNull { fileMapper.selectById(...) }`，
     * 每张图一次查询，是标准的 N+1。
     */
    override fun userImageBackgrounds(uid: String): List<UserFileVO> {
        val images = ktQuery()
            .eq(BackgroundImageEntity::uid, uid)
            .eq(BackgroundImageEntity::isDefault, false)
            .list()
        if (images.isEmpty()) return emptyList()

        val objects = ossObjectService.findByIds(images.map { it.fileId })
        return images.mapNotNull { image ->
            objects[image.fileId]?.let { obj ->
                UserFileVO(id = obj.id, fullName = obj.signedUrl(RENDER_SIZE).orEmpty())
            }
        }
    }

    /**
     * 删除用户自传的背景图。
     *
     * 先删 DB 行，成功后再删 OSS 对象：顺序反过来的话，一旦 DB 删除失败，库里就会留下一条
     * 指向已消失对象的记录。OSS 删除失败只记警告（见 [OssUtils.delete]），不回滚 ——
     * 用户视角图已经删掉了，残留对象由对账任务兜底。
     *
     * 账本行**只标记不删除**：留着这一行才能回答"这个 key 曾经存在、什么时候没的"。
     */
    override fun deleteUserImage(uid: String, id: String): Boolean {
        val fileId = baseMapper.selectById(id)?.takeIf { it.uid == uid && !it.isDefault }?.fileId
        val removed = ktUpdate().eq(BackgroundImageEntity::uid, uid).eq(BackgroundImageEntity::id, id)
            .eq(BackgroundImageEntity::isDefault, false).remove()
        if (removed && fileId != null) {
            ossObjectService.findById(fileId)?.let {
                OssUtils.delete(it.objectKey)
                ossObjectService.markDeleted(it.id)
            }
        }
        return removed
    }
}
