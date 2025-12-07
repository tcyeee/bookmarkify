package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.common.GradientConfig
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.BackSettingParams
import top.tcyeee.bookmarkify.entity.UserDelParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.UserInfoUptateParams

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
     * 更新背景颜色
     * @param config 背景颜色配置
     * @param uid 用户ID
     * @return 是否成功
     */
    fun updateBacColor(config: GradientConfig, uid: String): Boolean

    /**
     * 上传背景图片
     * @param file 背景图片文件
     * @param uid 用户ID
     * @return 背景图片路径 eg bacPic/1234567890.jpg
     */
    fun updateBacImg(file: MultipartFile, uid: String): String

    /**
     * 上传头像
     * @param file 头像文件
     * @param uid 用户ID
     * @return 头像路径 eg avatar/1234567890.jpg
     */
    fun updateAvatar(file: MultipartFile, uid: String): String

    /**
     * 背景设置
     * @param params 背景设置参数
     * @param uid 用户ID
     * @return 是否成功
     */
    fun bacSetting(params: BackSettingParams, uid: String): Boolean
}