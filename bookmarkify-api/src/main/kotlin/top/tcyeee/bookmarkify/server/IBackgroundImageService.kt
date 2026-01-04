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
    fun defaultImageBackgrounds(): List<UserFileVO>
    fun userImageBackgrounds(uid: String): List<UserFileVO>
    fun deleteUserImage(uid: String, id: String): Boolean
}
