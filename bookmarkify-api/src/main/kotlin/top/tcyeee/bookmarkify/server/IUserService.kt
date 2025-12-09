package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.BacSettingVO
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.BackSettingParams
import top.tcyeee.bookmarkify.entity.GradientConfigParams
import top.tcyeee.bookmarkify.entity.UserDelParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.UserInfoUptateParams
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.dto.UserSetting

/**
 * @author tcyeee
 * @date 3/11/25 20:01
 */
interface IUserService : IService<UserEntity> {
    /**
     * SESSION 注册用户信息
     * @param request   request
     * @param response  response
     * @return 用户基础信息+token (注意：这里不包含用户头像和用户设置)
     */
    fun track(request: HttpServletRequest, response: HttpServletResponse): UserSessionInfo

    /**
     * 获取用户信息
     *
     * @param uid uid
     * @return 用户基础信息 + 头像 + 设置 （没有TOKEN）
     */
    fun userInfo(uid: String): UserInfoShow

    /**
     * 更新背景颜色
     * @param params 背景颜色配置
     * @param uid 用户ID
     * @return 是否成功
     */
    fun addBacColor(params: GradientConfigParams, uid: String): Boolean

    /**
     * 上传背景图片
     * @param file 背景图片文件
     * @param uid 用户ID
     * @return 背景图片路径 eg bacPic/1234567890.jpg
     */
    fun addBacImg(file: MultipartFile, uid: String): String

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

    /**
     * 查询用户设置
     * @param uid 用户ID
     * @return 用户设置
     */
    fun queryUserSetting(uid: String): UserSetting

    /**
     * 查询用户背景设置
     * @param uid 用户ID
     * @return 用户背景设置
     */
    fun queryUserBacSetting(uid: String): BacSettingVO

    fun updateInfo(params: UserInfoUptateParams): Boolean
    fun updateUsername(username: String): Boolean
    fun changePhone(phone: String): Boolean
    fun checkPhone(code: Int): Boolean
    fun changeMail(mail: String): Boolean
    fun del(params: UserDelParams): Boolean
}