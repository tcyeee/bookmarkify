package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.entity.BacSettingVO
import top.tcyeee.bookmarkify.entity.BackSettingParams
import top.tcyeee.bookmarkify.entity.CaptchaSmsParams
import top.tcyeee.bookmarkify.entity.EmailVerifyParams
import top.tcyeee.bookmarkify.entity.GradientConfigParams
import top.tcyeee.bookmarkify.entity.SmsVerifyParams
import top.tcyeee.bookmarkify.entity.UserDelParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.UserInfoUpdateParams
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.dto.UserSetting
import top.tcyeee.bookmarkify.entity.entity.UserEntity

/**
 * @author tcyeee
 * @date 3/11/25 20:01
 */
interface IUserService : IService<UserEntity> {
    /**
     * SESSION 注册用户信息
     * @param request request
     * @param response response
     * @return 用户基础信息+token (注意：这里不包含用户头像和用户设置)
     */
    fun track(request: HttpServletRequest, response: HttpServletResponse): UserSessionInfo

    fun loginOut(response: HttpServletResponse)

    /**
     * 获取用户本人信息，如果数据库没有，则需要同步注册此人的TOKEN
     *
     * @param uid uid
     * @return 用户基础信息 + 头像 + 设置 （没有TOKEN）
     */
    fun me(uid: String): UserInfoShow

    /**
     * 更新背景颜色
     * @param params 背景颜色配置
     * @param uid 用户ID
     * @return 是否成功
     */
    fun addBacColor(params: GradientConfigParams, uid: String): Boolean

    /**
     * 上传背景图片
     * @param multipartFile 背景图片文件
     * @param uid 用户ID
     * @return 背景图片路径 eg bacPic/1234567890.jpg
     */
    fun addBacImg(multipartFile: MultipartFile, uid: String): String

    /**
     * 上传头像
     * @param multipartFile 头像文件
     * @param uid 用户ID
     * @return 头像路径 eg avatar/1234567890.jpg
     */
    fun updateAvatar(multipartFile: MultipartFile, uid: String): String

    /**
     * 背景设置
     * @param params 背景设置参数
     * @param uid 用户ID
     * @return 是否成功
     */
    fun bacSetting(params: BackSettingParams, uid: String): BacSettingVO

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

    /**
     * 更新用户信息
     * @param params 用户信息参数
     * @return 是否更新成功
     */
    fun updateInfo(params: UserInfoUpdateParams): Boolean

    /**
     * 更新用户名称
     * @param username 用户名称
     * @return 是否更新成功
     */
    fun updateUsername(username: String): Boolean

    /**
     * 修改手机号
     * @param phone 手机号
     * @return 是否修改成功
     */
    fun changePhone(phone: String): Boolean

    /**
     * 校验手机号
     * @param code 验证码
     * @return 是否校验成功
     */
    fun checkPhone(code: Int): Boolean

    /**
     * 修改邮箱
     * @param mail 邮箱
     * @return 是否修改成功
     */
    fun changeMail(mail: String): Boolean

    /**
     * 删除用户
     * @param params 删除参数
     * @return 是否删除成功
     */
    fun del(params: UserDelParams): Boolean

    /**
     * 获取图形验证码
     * @param uid 用户ID
     * @return 图形验证码结果
     */
    fun captchaImage(uid: String): ResultWrapper

    /**
     * 发送短信验证码
     * @param uid 用户ID
     * @param params 短信参数
     * @return 是否发送成功
     */
    fun sendSms(uid: String, params: CaptchaSmsParams): Boolean

    /**
     * 校验短信验证码
     * @param request 请求对象
     * @param response 响应对象
     * @param uid 用户ID
     * @param params 短信验证参数
     * @return 用户会话信息
     */
    fun verifySms(
        request: HttpServletRequest, response: HttpServletResponse, uid: String, params: SmsVerifyParams
    ): UserSessionInfo

    /**
     * 发送邮箱验证码
     * @param uid 用户ID
     * @param email 邮箱地址
     * @return 是否发送成功
     */
    fun sendEmail(uid: String, email: String): Boolean

    /**
     * 校验邮箱验证码
     * @param request 请求对象
     * @param response 响应对象
     * @param uid 用户ID
     * @param params 邮箱验证参数
     * @return 用户会话信息
     */
    fun verifyEmail(
        request: HttpServletRequest, response: HttpServletResponse, uid: String, params: EmailVerifyParams
    ): UserSessionInfo

    fun findByNameAndPwd(account: String, password: String): UserEntity?
}
