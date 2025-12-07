package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.entity.UserFile

/**
 * 文件记录 Service
 *
 * @author tcyeee
 * @date 12/7/25 15:05
 */
interface IFileService : IService<UserFile> {
    /**
     * 上传头像
     * @param uid 用户ID
     * @param file 头像文件
     * @return 头像路径
     */
    fun updateAvatar(uid: String, file: MultipartFile): String

    /**
     * 上传自定义背景图片
     * @param uid 用户ID
     * @param file 背景图片文件
     * @return 背景图片相对路径
     */
    fun uploadBackground(uid: String, file: MultipartFile): String
}
