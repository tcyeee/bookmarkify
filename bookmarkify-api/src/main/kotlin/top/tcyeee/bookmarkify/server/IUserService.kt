package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.BacSettingVO
import top.tcyeee.bookmarkify.entity.BackSettingParams
import top.tcyeee.bookmarkify.entity.EmailVerifyParams
import top.tcyeee.bookmarkify.entity.GoogleLoginParams
import top.tcyeee.bookmarkify.entity.GradientConfigParams
import top.tcyeee.bookmarkify.entity.AccountLoginParams
import top.tcyeee.bookmarkify.entity.ChangePasswordParams
import top.tcyeee.bookmarkify.entity.UserAdminVO
import top.tcyeee.bookmarkify.entity.UserDelParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.UserInfoUpdateParams
import top.tcyeee.bookmarkify.entity.UserSearchParams
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.dto.UserSetting
import top.tcyeee.bookmarkify.entity.entity.UserEntity

/**
 * @author tcyeee
 * @date 3/11/25 20:01
 */
interface IUserService : IService<UserEntity> {
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
     * 删除用户
     * @param params 删除参数
     * @return 是否删除成功
     */
    fun del(params: UserDelParams): Boolean

    /**
     * 发送邮箱验证码
     * @param email 邮箱地址
     * @return 本次发送的区分代码(2 位大写字母),用于前端展示帮助用户识别对应邮件;发送失败抛 [top.tcyeee.bookmarkify.config.exception.CommonException]
     */
    fun sendEmail(email: String): String

    /**
     * 校验邮箱验证码并登录（邮箱不存在则注册）
     * @param params 邮箱验证参数
     * @return 用户会话信息
     */
    fun verifyEmail(params: EmailVerifyParams): UserSessionInfo

    /**
     * 校验 Google ID Token 并登录（Google 邮箱不存在则注册）
     * @param params 含前端 Google Identity Services 返回的 ID Token
     * @return 用户会话信息
     */
    fun loginByGoogle(params: GoogleLoginParams): UserSessionInfo

    /**
     * 关联 Google 到当前已登录账户(严格一对一)
     * @param uid 当前用户ID
     * @param params 含 Google ID Token
     * @return 关联后的用户信息(含 googleEmail)
     */
    fun bindGoogle(uid: String, params: GoogleLoginParams): UserInfoShow

    /**
     * 解绑当前账户的 Google 关联(带安全检查,无其他登录凭证则拒绝)
     * @param uid 当前用户ID
     * @return 解绑后的用户信息
     */
    fun unbindGoogle(uid: String): UserInfoShow

    fun findByNameAndPwd(account: String, password: String): UserEntity?

    fun loginByAccount(params: AccountLoginParams): UserSessionInfo

    fun changePassword(uid: String, params: ChangePasswordParams): Boolean

    fun adminListAll(params: UserSearchParams): IPage<UserAdminVO>
}
