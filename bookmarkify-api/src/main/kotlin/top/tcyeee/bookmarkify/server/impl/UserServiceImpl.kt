package top.tcyeee.bookmarkify.server.impl

import java.util.Base64
import cn.hutool.core.util.IdUtil
import cn.hutool.core.util.RandomUtil
import cn.hutool.core.util.StrUtil
import cn.hutool.crypto.SecureUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.AccountLoginParams
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.dto.UserSetting
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.mapper.UserPreferenceMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils
import top.tcyeee.bookmarkify.utils.MailUtils
import top.tcyeee.bookmarkify.utils.PasswordUtils
import top.tcyeee.bookmarkify.utils.RedisUtils
import top.tcyeee.bookmarkify.utils.StpKit


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
    private val mailUtils: MailUtils,
    private val bookmarkService: IBookmarkService,
    private val userPreferenceMapper: UserPreferenceMapper
) : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    /**
     * 获取用户信息
     * @param uid uid
     * @return 用户基础信息 + 头像 + 设置 （没有TOKEN）
     */
    override fun me(uid: String): UserInfoShow =
        (getById(uid) ?: throw CommonException(ErrorType.E215))
            .let { UserInfoShow(it, it.avatarUrlWithSign()) }

    fun UserEntity.avatarUrlWithSign(): String? {
        if (StrUtil.isBlank(this.avatarFileId)) return null
        return fileMapper.selectById(this.avatarFileId)?.fullUrlWithSign(300)
    }

    /**
     * 创建一个已验证的正式用户，并初始化默认数据（功能 / 偏好 / 书签）。
     * @param email 绑定的邮箱
     * @return 新建用户实体
     */
    private fun createVerifiedUser(email: String): UserEntity {
        val userEntity = UserEntity(IdUtil.fastUUID()).apply {
            this.email = email
            this.verified = true
        }
        save(userEntity)
        // 初始化用户设置
        bookmarkService.setDefaultFunction(userEntity.id)
        // 初始化用户偏好设置
        userPreferenceMapper.insert(UserPreferenceEntity(uid = userEntity.id))
        // 初始化用户书签
        bookmarkService.setDefaultBookmark(userEntity.id)
        return userEntity
    }

    override fun loginOut(response: HttpServletResponse) {
        // 清除session
        StpKit.USER.session.clear()
        StpKit.USER.logout()
        Cookie(projectConfig.uidCookieName, "").apply { maxAge = 0; path = "/" }.also { response.addCookie(it) }
    }

    override fun sendEmail(email: String): String {
        // 邮箱统一归一化(trim + lowercase):发码与验码必须用同一份键,否则验证码读不到;
        // 同时保证 DB 唯一性,避免大小写差异注册出重复账号。
        val normalized = email.trim().lowercase()
        val code = RandomUtil.randomInt(1000, 9999).toString()
        // 区分代码:2 位大写字母,纯展示用途,帮助用户在收件箱里识别本次请求对应的那封邮件,避免用错旧验证码。
        // 用字母与数字验证码区隔形态,降低混淆;不参与校验、不入 Redis。
        val ref = (1..2).map { ('A'..'Z').random() }.joinToString("")
        val success = mailUtils.send(normalized, MailUtils.EmailType.VERIFY_CODE, code, ref)
        if (!success) throw CommonException(ErrorType.E106)
        RedisUtils.set(RedisType.CODE_EMAIL, normalized, code)
        return ref
    }

    override fun loginByAccount(params: AccountLoginParams): UserSessionInfo {
        // 账号(邮箱/手机)归一化,与邮箱验证码登录同口径,避免大小写差异登不进;手机号 lowercase 为无副作用
        val account = String(Base64.getDecoder().decode(params.account)).trim().lowercase()
        // 客户端传来的是 Base64(md5(明文))，解码后即规范凭据（md5 串），不再用它做整列等值查询
        val credential = String(Base64.getDecoder().decode(params.password))
        val user = ktQuery()
            .and { it.eq(UserEntity::email, account).or().eq(UserEntity::phone, account) }.one()
            ?: throw CommonException(ErrorType.E110)
        if (user.disabled) throw CommonException(ErrorType.E110)
        if (!PasswordUtils.matches(credential, user.password)) throw CommonException(ErrorType.E110)
        upgradePasswordIfLegacy(user, credential)
        StpKit.USER.logout()
        StpKit.USER.login(user.id, true)
        return user.authVO(StpKit.USER.tokenValue).writeToSession()
    }

    override fun changePassword(uid: String, params: ChangePasswordParams): Boolean {
        val oldCredential = String(Base64.getDecoder().decode(params.oldPassword))
        val newCredential = String(Base64.getDecoder().decode(params.newPassword))
        val user = getById(uid) ?: throw CommonException(ErrorType.E110)
        if (!PasswordUtils.matches(oldCredential, user.password)) throw CommonException(ErrorType.E110)
        return ktUpdate().eq(UserEntity::id, uid)
            .set(UserEntity::password, PasswordUtils.encode(newCredential)).update()
    }

    override fun findByNameAndPwd(account: String, password: String): UserEntity? {
        val credential = SecureUtil.md5(password)
        // 账号(邮箱/手机)归一化,与其他登录路径同口径
        val normalized = account.trim().lowercase()
        val user = ktQuery()
            .and { it.eq(UserEntity::email, normalized).or().eq(UserEntity::phone, normalized) }.one()
            ?: return null
        if (!PasswordUtils.matches(credential, user.password)) return null
        upgradePasswordIfLegacy(user, credential)
        return user
    }

    /** 旧的明文 md5 行在校验通过后，原地升级为带盐 BCrypt（登录即升级） */
    private fun upgradePasswordIfLegacy(user: UserEntity, credential: String) {
        if (PasswordUtils.needsUpgrade(user.password)) {
            ktUpdate().eq(UserEntity::id, user.id)
                .set(UserEntity::password, PasswordUtils.encode(credential)).update()
        }
    }

    /**
     * 校验邮箱验证码并登录：
     * - 邮箱已存在 => 登录该账户
     * - 邮箱不存在 => 注册一个新的正式用户并登录
     */
    @Transactional
    override fun verifyEmail(params: EmailVerifyParams): UserSessionInfo {
        // 与 sendEmail 保持同一归一化口径(trim + lowercase)
        val email = params.email.trim().lowercase()
        val cacheCode = RedisUtils.get<String>(RedisType.CODE_EMAIL, email) ?: throw CommonException(ErrorType.E105)
        if (cacheCode != params.code.trim()) throw CommonException(ErrorType.E301)
        RedisUtils.del(RedisType.CODE_EMAIL, email)

        val userEntity = ktQuery().eq(UserEntity::email, email).one() ?: createVerifiedUser(email)
        // 该接口为公开端点,正常调用时无登录态;若携带了有效旧 token 则先登出再切换账户
        if (StpKit.USER.isLogin) StpKit.USER.logout()
        StpKit.USER.login(userEntity.id, true)
        return userEntity.authVO(StpKit.USER.tokenValue).writeToSession()
    }

    override fun queryUserBacSetting(uid: String): BacSettingVO {
        return backSettingService.queryShowByUid(uid)
    }

    override fun queryUserSetting(uid: String): UserSetting = UserSetting(bacSetting = queryUserBacSetting(uid))

    override fun bacSetting(params: BackSettingParams, uid: String): BacSettingVO {
        val entity = backSettingService.ktQuery().eq(BackgroundConfigEntity::uid, uid).one()
            // 如果查询到了，则修改其中的参数
            // 如果没有查询到，则创建对象
            ?.also { it.updateParams(params) } ?: BackgroundConfigEntity(
            uid = uid, type = params.type, backgroundLinkId = params.backgroundId
        )
        backSettingService.saveOrUpdate(entity)

        return backSettingService.queryShowByUid(uid)
    }

    override fun updateInfo(params: UserInfoUpdateParams): Boolean {
        if (params.nickName.isBlank()) return false
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::nickName, params.nickName).update()
    }

    override fun addBacColor(params: GradientConfigParams, uid: String): Boolean {
        val currentCount = bacGradientService.ktQuery().eq(BackgroundGradientEntity::uid, uid)
            .eq(BackgroundGradientEntity::isDefault, false).count()
        if (currentCount >= projectConfig.maxCustomBackgroundCount) throw CommonException(
            ErrorType.E102, "自定义渐变最多 ${projectConfig.maxCustomBackgroundCount} 个"
        )

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
        val currentCount =
            bacImageService.ktQuery().eq(BackgroundImageEntity::uid, uid).eq(BackgroundImageEntity::isDefault, false)
                .count()
        if (currentCount >= projectConfig.maxCustomBackgroundCount) throw CommonException(
            ErrorType.E102, "自定义图片最多 ${projectConfig.maxCustomBackgroundCount} 个"
        )

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

    override fun del(params: UserDelParams): Boolean {
        val uid = BaseUtils.uid()
        val user = getById(uid) ?: throw CommonException(ErrorType.E110)
        if (!PasswordUtils.matches(SecureUtil.md5(params.password), user.password)) {
            throw CommonException(ErrorType.E110)
        }
        return ktUpdate().eq(UserEntity::id, uid).set(UserEntity::deleted, true).update()
    }

    override fun updateUsername(username: String): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::nickName, username).update()

    // 旧的 changePhone / checkPhone / changeMail 实现绕过验证码、且 checkPhone 把数字验证码写进 email 字段，已删除。
    // 改绑/绑定走 verifySms / verifyEmail 走验证码校验路径。

    override fun adminListAll(params: UserSearchParams): IPage<UserAdminVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { UserAdminVO(it) }
}
