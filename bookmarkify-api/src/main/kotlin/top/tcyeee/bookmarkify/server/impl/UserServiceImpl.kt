package top.tcyeee.bookmarkify.server.impl

import cn.dev33.satoken.stp.StpUtil
import cn.hutool.captcha.CaptchaUtil
import cn.hutool.core.util.RandomUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.dto.UserSetting
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils
import top.tcyeee.bookmarkify.utils.MailUtils
import top.tcyeee.bookmarkify.utils.RedisUtils
import top.tcyeee.bookmarkify.utils.pwd

/**
 * @author tcyeee
 * @date 3/11/25 20:02
 */
@Service
class UserServiceImpl(
    private val backSettingService: BackgroundConfigServiceImpl,
    private val bacGradientService: BackgroundGradientServiceImpl,
    private val bacImageService: BackgroundImageServiceImpl,
    private val fileMapper: FileMapper,
    private val fileService: FileServiceImpl,
    private val projectConfig: ProjectConfig,
    private val smsService: SmsServiceImpl,
    private val mailUtils: MailUtils,
) : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    /**
     * 获取用户信息
     * @param uid uid
     * @return 用户基础信息 + 头像 + 设置 （没有TOKEN）
     */
    override fun me(uid: String): UserInfoShow = getById(uid)?.vo()?.apply {
        avatar = fileMapper.selectById(this.avatarFileId)
        userSetting = queryUserSetting(uid)
    } ?: run {
        this.loginOut()
        throw CommonException(ErrorType.E202)
    }

    /**
     * 注册用户信息
     * @param request request
     * @param response response
     * @return 用户基础信息+token (注意：这里不包含用户头像和用户设置)
     */
    override fun track(
        request: HttpServletRequest, response: HttpServletResponse
    ): UserSessionInfo = if (StpUtil.isLogin() && BaseUtils.user() != null) {
        BaseUtils.user()!!
    } else {
        BaseUtils.sessionRegisterDeviceId(request, response, projectConfig).let(this::queryOrRegisterByDeviceId)
            .also { StpUtil.login(it.id, true) }.authVO(StpUtil.getTokenValue()).writeToSession()
    }

    override fun loginOut() {
        StpUtil.getSession().clear()
        StpUtil.logout()
    }

    override fun sendSms(uid: String, params: CaptchaSmsParams): Boolean {
        val cache = RedisUtils.get<String>(RedisType.CAPTCHA_CODE, uid) ?: return false
        if (cache != params.captcha.trim()) return false
        smsService.sendVerificationCode(params.phone)
        return true
    }

    /**
     * 验证码登录/验证/绑定
     *
     * # 可能使用到该接口的情况:
     * 1. 用户正在使用验证码登录 => 将当前临时账户和手机号所在帐户合并
     * 2. 用户正在绑定/更换手机号 => 在Session中更新, 返回当前帐户信息
     */
    override fun verifySms(
        request: HttpServletRequest, response: HttpServletResponse, uid: String, params: SmsVerifyParams
    ): UserSessionInfo {
        val cacheCode =
            RedisUtils.get<Int>(RedisType.CODE_PHONE, uid)?.toString() ?: throw CommonException(ErrorType.E105)
        if (cacheCode != params.smsCode.trim()) throw CommonException(ErrorType.E301)

        val userEntity: UserEntity? = ktQuery().eq(UserEntity::phone, params.phone.trim()).one()
        // 手机号已经有对应帐户了,说明是在登录
        if (userEntity != null) {
            // 先退出登录,然后重新注册Session
            this.loginOut()
            StpUtil.login(userEntity.id, true)

            // TODO 检查是否有必要进行数据合并,临时账户中的信息保存一天时间,超出则不在提示用户

            // 重新在session中存储UserInfo
            return userEntity.authVO(StpUtil.getTokenValue()).writeToSession()
        } else {
            // 如果没有,则将手机号绑定到当前用户上
            ktUpdate().eq(UserEntity::id, uid).set(UserEntity::phone, params.phone.trim())
                .set(UserEntity::verified, true).update()

            // 更新sesssion中的信息(加上手机号)
            val result = BaseUtils.user() ?: throw CommonException(ErrorType.E215)
            result.phone = params.phone.trim()
            result.verified = true
            result.writeToSession()

            return result
        }
    }

    override fun sendEmail(uid: String, email: String): Boolean {
        val code = RandomUtil.randomInt(1000, 999999).toString()
        val success = mailUtils.send(email, MailUtils.EmailType.VERIFY_CODE, code)
        if (success) RedisUtils.set(RedisType.CODE_EMAIL, uid, code)
        return success
    }

    override fun verifyEmail(
        request: HttpServletRequest, response: HttpServletResponse, uid: String, params: EmailVerifyParams
    ): UserSessionInfo {
        val cacheCode = RedisUtils.get<String>(RedisType.CODE_EMAIL, uid) ?: throw CommonException(ErrorType.E105)
        if (cacheCode != params.code.trim()) throw CommonException(ErrorType.E301)

        val userEntity: UserEntity? = ktQuery().eq(UserEntity::email, params.email.trim()).one()
        // 邮箱已经有对应帐户了,说明是在登录
        if (userEntity != null) {
            // 先退出登录,然后重新注册Session
            this.loginOut()
            StpUtil.login(userEntity.id, true)

            // 重新在session中存储UserInfo
            return userEntity.authVO(StpUtil.getTokenValue()).writeToSession()
        } else {
            // 如果没有,则将邮箱绑定到当前用户上
            ktUpdate().eq(UserEntity::id, uid).set(UserEntity::email, params.email.trim())
                .set(UserEntity::verified, true).update()

            // 清除Redis
            RedisUtils.del(RedisType.CODE_EMAIL, uid)

            // 更新sesssion中的信息(加上邮箱)
            val result = BaseUtils.user() ?: throw CommonException(ErrorType.E215)
            result.email = params.email.trim()
            result.verified = true
            result.writeToSession()

            return result
        }
    }

    override fun captchaImage(uid: String): ResultWrapper {
        val lineCaptcha = CaptchaUtil.createLineCaptcha(200, 100)
        val code = lineCaptcha.code
        RedisUtils.set(RedisType.CAPTCHA_CODE, uid, code)
        return ResultWrapper.ok(lineCaptcha.imageBase64)
    }

    /**
     * 查询或者注册拿到用户信息
     * @param deviceId 用户唯一ID（后端生成）
     * @return 用户信息
     */
    private fun queryOrRegisterByDeviceId(deviceId: String): UserEntity =
        ktQuery().eq(UserEntity::deviceId, deviceId).one() ?: UserEntity(deviceId).also { save(it) }

    override fun queryUserBacSetting(uid: String): BacSettingVO {
        val result = backSettingService.queryByUid(uid).vo()

        if (result.type == BackgroundType.GRADIENT) {
            bacGradientService.getById(result.backgroundLinkId).apply {
                result.bacColorGradient = this.gradientArray()
                result.bacColorDirection = this.direction
            }
        }

        if (result.type == BackgroundType.IMAGE) {
            bacImageService.getFileById(result.backgroundLinkId).apply { result.bacImgFile = this }
        }

        return result
    }

    override fun queryUserSetting(uid: String): UserSetting = UserSetting(bacSetting = queryUserBacSetting(uid))

    override fun bacSetting(params: BackSettingParams, uid: String): Boolean {
        val entity = backSettingService.ktQuery().eq(BackgroundConfigEntity::uid, uid).one()
            // 如果查询到了，则修改其中的参数
            ?.also { it.updateParams(params) }
        // 如果没有查询到，则创建对象
            ?: BackgroundConfigEntity(
                uid = uid, type = params.type, backgroundLinkId = params.backgroundId
            )
        backSettingService.saveOrUpdate(entity)
        return true
    }

    override fun updateInfo(params: UserInfoUptateParams): Boolean {
        if (params.nickName.isBlank()) return false
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::nickName, params.nickName).update()
    }

    override fun addBacColor(params: GradientConfigParams, uid: String): Boolean {
        val entity = BackgroundGradientEntity(
            uid = uid,
            gradient = JSONUtil.toJsonStr(params.colors),
            direction = params.direction,
        ).also { bacGradientService.save(it) }

        backSettingService.ktUpdate().eq(BackgroundConfigEntity::uid, uid)
            .set(BackgroundConfigEntity::type, BackgroundType.GRADIENT)
            .set(BackgroundConfigEntity::backgroundLinkId, entity.id).update()
        return true
    }

    override fun addBacImg(multipartFile: MultipartFile, uid: String): String {
        val file = fileService.uploadBackground(uid, multipartFile)

        // 添加到背景图片数据库
        val bacImgEntity = BackgroundImageEntity(uid = uid, fileId = file.id).also { bacImageService.save(it) }

        // 修改用户背景图片设置
        backSettingService.queryByUid(uid).apply {
                this.uid = uid
                this.type = BackgroundType.IMAGE
                this.backgroundLinkId = bacImgEntity.id
            }.also { backSettingService.saveOrUpdate(it) }
        return file.currentName
    }

    override fun updateAvatar(multipartFile: MultipartFile, uid: String): String {
        val file = fileService.updateAvatar(BaseUtils.uid(), multipartFile)
        ktUpdate().eq(UserEntity::id, uid).set(UserEntity::avatarFileId, file.id).update()
        return file.currentName
    }

    override fun del(params: UserDelParams): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).eq(UserEntity::password, pwd(params.password))
            .set(UserEntity::deleted, true).update()

    override fun updateUsername(username: String): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::nickName, username).update()

    override fun changePhone(phone: String): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::phone, phone).set(UserEntity::verified, true)
            .update()

    override fun checkPhone(code: Int): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::email, code).update()

    override fun changeMail(mail: String): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::email, mail).update()
}
