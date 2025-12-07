package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.common.BackgroundConfig
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.request.UserDelParams
import top.tcyeee.bookmarkify.entity.request.UserInfoUptateParams
import top.tcyeee.bookmarkify.entity.response.UserInfoShow

/**
 * @author tcyeee
 * @date 3/11/25 20:01
 */
interface IUserService : IService<UserEntity> {

    /**
     * 通过谷歌ID/浏览器缓存ID/浏览器指纹ID 来寻找用户
     * 这三个根据变动概率来进行优先级排序
     *
     * @return 用户信息
     */
    fun getByDeviceId(deviceId: String): UserEntity?
    fun createUserByDeviceId(deviceId: String): UserEntity
    fun userInfo(): UserInfoShow
    fun updateInfo(params: UserInfoUptateParams): Boolean
    fun updateUsername(username: String): Boolean
    fun changePhone(phone: String): Boolean
    fun checkPhone(code: Int): Boolean
    fun changeMail(mail: String): Boolean
    fun del(params: UserDelParams): Boolean

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

    /**
     * 更新主页背景配置（包含渐变/图片等配置）
     */
    fun updateBackgroundConfig(config: BackgroundConfig): Boolean
}