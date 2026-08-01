package top.tcyeee.bookmarkify.server

import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity

/**
 * 用户文件上传 Service。
 *
 * 不再继承 `IService<UserFile>`：`user_file` 已被 `oss_object` 取代，这里只剩"传桶并记一笔"
 * 这一个动作，挂一整套 CRUD 基类只会把已经退场的表重新暴露出来。
 *
 * @author tcyeee
 * @date 12/7/25 15:05
 */
interface IFileService {
    /**
     * 上传头像
     * @param uid 用户ID
     * @param file 头像文件
     */
    fun updateAvatar(uid: String, file: MultipartFile): OssObjectEntity

    /**
     * 上传自定义背景图片
     * @param uid 用户ID
     * @param file 背景图片文件
     */
    fun uploadBackground(uid: String, file: MultipartFile): OssObjectEntity
}
