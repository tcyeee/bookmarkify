package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.UserFileVO
import top.tcyeee.bookmarkify.entity.entity.BackgroundImageEntity
import top.tcyeee.bookmarkify.entity.entity.UserFile

/**
 * 用户图片背景 Service
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
interface IBackgroundImageService : IService<BackgroundImageEntity> {
    fun getFileById(id: String): UserFile

    /**
     * @param uid uid
     * @param linkId 关联的背景图片ID,或者背景渐变ID
     * @return 处理好的图片URL  用户可能是用的默认图片,默认图片是没有存储在数据库中的,此时需要利用拼接方法拿到真实的URL
     */
    fun currentBacImgUrl(uid: String, linkId: String): String
    fun defaultImageBackgrounds(): List<UserFileVO>
    fun userImageBackgrounds(uid: String): List<UserFileVO>
    fun deleteUserImage(uid: String, id: String): Boolean
}
