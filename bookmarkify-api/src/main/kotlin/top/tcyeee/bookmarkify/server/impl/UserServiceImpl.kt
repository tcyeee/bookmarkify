package top.tcyeee.bookmarkify.server.impl

import java.util.Base64
import cn.hutool.core.util.IdUtil
import cn.hutool.core.util.RandomUtil
import cn.hutool.core.util.StrUtil
import cn.hutool.crypto.SecureUtil
import cn.hutool.http.HttpUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.cache.RedisType
import org.slf4j.LoggerFactory
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.AccountLoginParams
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.dto.UserSetting
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.mapper.UserPreferenceMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IFileService
import top.tcyeee.bookmarkify.server.IOssObjectService
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.server.admin.AdminUserViewAssembler
import top.tcyeee.bookmarkify.utils.BaseUtils
import top.tcyeee.bookmarkify.utils.CurrentEnvironment
import top.tcyeee.bookmarkify.utils.MailUtils
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.PasswordUtils
import top.tcyeee.bookmarkify.utils.RedisUtils
import top.tcyeee.bookmarkify.utils.StpKit
import top.tcyeee.bookmarkify.utils.currentEnvironment


/**
 * @author tcyeee
 * @date 3/11/25 20:02
 */
@Service
class UserServiceImpl(
    private val backSettingService: BackgroundConfigServiceImpl,
    private val bacGradientService: BackgroundGradientServiceImpl,
    private val bacImageService: BackgroundImageServiceImpl,
    private val ossObjectService: IOssObjectService,
    private val fileService: IFileService,
    private val projectConfig: ProjectConfig,
    private val mailUtils: MailUtils,
    private val bookmarkService: IBookmarkService,
    private val userPreferenceMapper: UserPreferenceMapper,
    private val adminUserViewAssembler: AdminUserViewAssembler,
) : IUserService, ServiceImpl<UserMapper, UserInfoEntity>() {

    // 不能用全局的 `log` 扩展属性: ServiceImpl 自带一个 org.apache.ibatis.logging.Log 成员会把它遮蔽,
    // 那个接口没有 info() 也没有占位符重载。同 SiteServiceImpl / BookmarkCategoryServiceImpl 的做法。
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 获取用户信息
     * @param uid uid
     * @return 用户基础信息 + 头像 + 设置 （没有TOKEN）
     */
    override fun me(uid: String): UserInfoShow =
        (getById(uid) ?: throw CommonException(ErrorType.E215))
            .let { UserInfoShow(it, it.avatarPath()) }

    // 返回原始 OSS 路径（如 avatar/xxx.svg），不签名，可安全持久化
    fun UserInfoEntity.avatarPath(): String? =
        ossObjectService.findById(this.avatarFileId)?.objectKey

    override fun avatarSignedUrl(uid: String): String? {
        val user = getById(uid) ?: return null
        return ossObjectService.findById(user.avatarFileId)?.signedUrl(300)
    }

    /**
     * 创建一个已验证的正式用户，并初始化默认数据（功能 / 偏好 / 书签）。
     * @param email 绑定的邮箱
     * @return 新建用户实体
     */
    private fun createVerifiedUser(email: String): UserInfoEntity {
        val userEntity = UserInfoEntity(IdUtil.fastUUID()).apply {
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
        // 账号(邮箱)归一化,与邮箱验证码登录同口径,避免大小写差异登不进
        val account = String(Base64.getDecoder().decode(params.account)).trim().lowercase()
        // 客户端传来的是 Base64(md5(明文))，解码后即规范凭据（md5 串），不再用它做整列等值查询
        val credential = String(Base64.getDecoder().decode(params.password))
        val user = ktQuery().eq(UserInfoEntity::email, account).eq(UserInfoEntity::deleted, false).one()
            ?: throw CommonException(ErrorType.E110)
        if (user.disabled) throw CommonException(ErrorType.E110)
        if (!PasswordUtils.matches(credential, user.password)) throw CommonException(ErrorType.E110)
        upgradePasswordIfLegacy(user, credential)
        StpKit.USER.logout()
        StpKit.USER.login(user.id, true)
        return user.authVO(StpKit.USER.tokenValue).writeToSession()
    }

    /** 测试环境快捷登录：仅本地环境（ENV=local）开放，登录/自动创建固定测试账号，免密码 */
    override fun quickLogin(): UserSessionInfo {
        if (currentEnvironment() != CurrentEnvironment.LOCAL) throw CommonException(ErrorType.E120)
        val email = "quick-login-test@bookmarkify.local"
        val userEntity = ktQuery().eq(UserInfoEntity::email, email).eq(UserInfoEntity::deleted, false).one() ?: createVerifiedUser(email)
        if (StpKit.USER.isLogin) StpKit.USER.logout()
        StpKit.USER.login(userEntity.id, true)
        return userEntity.authVO(StpKit.USER.tokenValue).writeToSession()
    }

    override fun changePassword(uid: String, params: ChangePasswordParams): Boolean {
        val oldCredential = String(Base64.getDecoder().decode(params.oldPassword))
        val newCredential = String(Base64.getDecoder().decode(params.newPassword))
        val user = getById(uid) ?: throw CommonException(ErrorType.E110)
        if (!PasswordUtils.matches(oldCredential, user.password)) throw CommonException(ErrorType.E110)
        return ktUpdate().eq(UserInfoEntity::id, uid)
            .set(UserInfoEntity::password, PasswordUtils.encode(newCredential)).update()
    }

    override fun findByNameAndPwd(account: String, password: String): UserInfoEntity? {
        val credential = SecureUtil.md5(password)
        // 账号(邮箱)归一化,与其他登录路径同口径
        val normalized = account.trim().lowercase()
        val user = ktQuery().eq(UserInfoEntity::email, normalized).eq(UserInfoEntity::deleted, false).one()
            ?: return null
        if (!PasswordUtils.matches(credential, user.password)) return null
        upgradePasswordIfLegacy(user, credential)
        return user
    }

    /** 旧的明文 md5 行在校验通过后，原地升级为带盐 BCrypt（登录即升级） */
    private fun upgradePasswordIfLegacy(user: UserInfoEntity, credential: String) {
        if (PasswordUtils.needsUpgrade(user.password)) {
            ktUpdate().eq(UserInfoEntity::id, user.id)
                .set(UserInfoEntity::password, PasswordUtils.encode(credential)).update()
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

        val userEntity = ktQuery().eq(UserInfoEntity::email, email).eq(UserInfoEntity::deleted, false).one() ?: createVerifiedUser(email)
        // 该接口为公开端点,正常调用时无登录态;若携带了有效旧 token 则先登出再切换账户
        if (StpKit.USER.isLogin) StpKit.USER.logout()
        StpKit.USER.login(userEntity.id, true)
        return userEntity.authVO(StpKit.USER.tokenValue).writeToSession()
    }

    /**
     * 校验 Google ID Token 并登录,按以下优先级匹配账户:
     * 1. google_id 命中 => 登录该账户
     * 2. 未命中 => 按 Google 邮箱查现有账户,命中则自动回填 google_id/google_email 完成关联(兼容老用户)
     * 3. 都没有 => 注册一个新的正式用户并写入 google_id/google_email
     *
     * ID Token 的签名校验交由 Google 的 tokeninfo 端点完成(服务器需能访问 Google)。
     */
    @Transactional
    override fun loginByGoogle(params: GoogleLoginParams): UserSessionInfo {
        val identity = verifyGoogleIdToken(params.idToken)

        // 1. 先按稳定唯一标识 google_id 查找已关联账户
        val userEntity = ktQuery().eq(UserInfoEntity::googleId, identity.googleId).eq(UserInfoEntity::deleted, false).one()
        // 2. 未命中则按 Google 邮箱兑现现有账户并自动回填关联
            ?: ktQuery().eq(UserInfoEntity::email, identity.email).eq(UserInfoEntity::deleted, false).one()?.also {
                it.googleId = identity.googleId
                it.googleEmail = identity.email
                updateById(it)
            }
            // 3. 都没有则注册新用户
            ?: createVerifiedUser(identity.email).also {
                it.googleId = identity.googleId
                it.googleEmail = identity.email
                updateById(it)
            }

        if (StpKit.USER.isLogin) StpKit.USER.logout()
        StpKit.USER.login(userEntity.id, true)
        return userEntity.authVO(StpKit.USER.tokenValue).writeToSession()
    }

    /**
     * 用 GitHub 授权码登录,匹配优先级(与 loginByGoogle 结构对称):
     * 1. github_id 命中 => 登录该账户
     * 2. 未命中 => 按已验证主邮箱查现有账户,命中则回填 github_id/github_login
     * 3. 都没有 => 注册新用户并写入 github_id/github_login
     * GitHub 必须提供已验证主邮箱(verifyGithubCode 保证);无邮箱路径已移除。
     */
    @Transactional
    override fun loginByGithub(params: GithubLoginParams): UserSessionInfo {
        val identity = verifyGithubCode(params)

        val userEntity = ktQuery().eq(UserInfoEntity::githubId, identity.githubId).eq(UserInfoEntity::deleted, false).one()
            ?: ktQuery().eq(UserInfoEntity::email, identity.email).eq(UserInfoEntity::deleted, false).one()?.also {
                it.githubId = identity.githubId
                it.githubLogin = identity.login
                updateById(it)
            }
            ?: createVerifiedUser(identity.email).also {
                it.githubId = identity.githubId
                it.githubLogin = identity.login
                updateById(it)
            }

        if (StpKit.USER.isLogin) StpKit.USER.logout()
        StpKit.USER.login(userEntity.id, true)
        return userEntity.authVO(StpKit.USER.tokenValue).writeToSession()
    }

    /**
     * 关联 Google 到当前已登录账户(严格一对一):
     * - 该 Google 已被其他账户关联 => E113
     * - 当前账户已关联其他 Google => E114
     */
    @Transactional
    override fun bindGoogle(uid: String, params: GoogleLoginParams): UserInfoShow {
        val identity = verifyGoogleIdToken(params.idToken)
        val user = getById(uid) ?: throw CommonException(ErrorType.E215)

        if (!user.googleId.isNullOrBlank()) throw CommonException(ErrorType.E114)

        val occupied = ktQuery().eq(UserInfoEntity::googleId, identity.googleId).eq(UserInfoEntity::deleted, false).one()
        if (occupied != null) throw CommonException(ErrorType.E113)

        user.googleId = identity.googleId
        user.googleEmail = identity.email
        updateById(user)
        return UserInfoShow(user, user.avatarPath())
    }

    /**
     * 解绑当前账户的 Google 关联。
     * 安全检查: 解绑后若账户既无密码又无可登录邮箱,则无任何登录凭证,拒绝解绑(E115)。
     */
    @Transactional
    override fun unbindGoogle(uid: String): UserInfoShow {
        val user = getById(uid) ?: throw CommonException(ErrorType.E215)
        if (user.googleId.isNullOrBlank()) return UserInfoShow(user, user.avatarPath())

        val hasOtherCredential = !user.password.isNullOrBlank() || !user.email.isNullOrBlank()
        if (!hasOtherCredential) throw CommonException(ErrorType.E115)

        user.googleId = null
        user.googleEmail = null
        // MyBatis-Plus updateById 默认忽略 null 字段,需用 UpdateWrapper 显式置空
        ktUpdate()
            .set(UserInfoEntity::googleId, null)
            .set(UserInfoEntity::googleEmail, null)
            .eq(UserInfoEntity::id, uid)
            .update()
        return UserInfoShow(user, user.avatarPath())
    }

    /**
     * 关联 GitHub 到当前已登录账户(严格一对一):
     * - 当前账户已关联其他 GitHub => E119
     * - 该 GitHub 已被其他账户关联 => E118
     */
    @Transactional
    override fun bindGithub(uid: String, params: GithubLoginParams): UserInfoShow {
        val identity = verifyGithubCode(params)
        val user = getById(uid) ?: throw CommonException(ErrorType.E215)

        if (!user.githubId.isNullOrBlank()) throw CommonException(ErrorType.E119)

        val occupied = ktQuery().eq(UserInfoEntity::githubId, identity.githubId).eq(UserInfoEntity::deleted, false).one()
        if (occupied != null) throw CommonException(ErrorType.E118)

        user.githubId = identity.githubId
        user.githubLogin = identity.login
        updateById(user)
        return UserInfoShow(user, user.avatarPath())
    }

    /** 解绑当前账户的 GitHub 关联。解绑后若无密码且无可登录邮箱 => E115。 */
    @Transactional
    override fun unbindGithub(uid: String): UserInfoShow {
        val user = getById(uid) ?: throw CommonException(ErrorType.E215)
        if (user.githubId.isNullOrBlank()) return UserInfoShow(user, user.avatarPath())

        val hasOtherCredential = !user.password.isNullOrBlank() || !user.email.isNullOrBlank()
        if (!hasOtherCredential) throw CommonException(ErrorType.E115)

        user.githubId = null
        user.githubLogin = null
        // MyBatis-Plus updateById 默认忽略 null 字段,需用 UpdateWrapper 显式置空
        ktUpdate()
            .set(UserInfoEntity::githubId, null)
            .set(UserInfoEntity::githubLogin, null)
            .eq(UserInfoEntity::id, uid)
            .update()
        return UserInfoShow(user, user.avatarPath())
    }

    /**
     * 调用 Google tokeninfo 端点校验 ID Token,返回归一化后的 Google 身份(sub + 已验证邮箱)。
     * 校验项: 签名(由 Google 完成) + aud(必须等于本站 ClientId) + iss + email_verified。
     */
    private fun verifyGoogleIdToken(idToken: String): GoogleIdentity {
        val clientId = projectConfig.googleClientId
        if (clientId.isBlank()) throw CommonException(ErrorType.E111, "服务端未配置 Google ClientId")
        if (idToken.isBlank()) throw CommonException(ErrorType.E111)

        val response = runCatching {
            HttpUtil.createGet("https://oauth2.googleapis.com/tokeninfo")
                .form("id_token", idToken)
                .timeout(8000)
                .apply {
                    // 国内服务器无法直连 Google,经配置的 HTTP 代理(如 docker 内的 clash:7890)转发;
                    // 仅作用于本次请求,不影响 OSS / DeepSeek / 企业微信等国内服务的直连
                    if (projectConfig.googleProxyHost.isNotBlank() && projectConfig.googleProxyPort > 0) {
                        setHttpProxy(projectConfig.googleProxyHost, projectConfig.googleProxyPort)
                    }
                }
                .execute()
        }.getOrElse { throw CommonException(ErrorType.E111, "无法连接 Google 校验服务") }

        if (!response.isOk) throw CommonException(ErrorType.E111)

        val claims = runCatching { JSONUtil.parseObj(response.body()) }
            .getOrElse { throw CommonException(ErrorType.E111) }

        // aud 必须等于本站签发的 Client ID,否则可能是别处签发的令牌
        if (claims.getStr("aud") != clientId) throw CommonException(ErrorType.E111, "Client ID 不匹配")
        // 签发方校验
        val iss = claims.getStr("iss")
        if (iss != "accounts.google.com" && iss != "https://accounts.google.com") {
            throw CommonException(ErrorType.E111, "非法的令牌签发方")
        }
        // sub 是 Google 账号的稳定唯一标识(邮箱可变 sub 不变),作为关联主键
        val sub = claims.getStr("sub")?.trim()
        if (sub.isNullOrBlank()) throw CommonException(ErrorType.E111, "未获取到 Google 标识")
        // 邮箱必须存在且已被 Google 验证
        val email = claims.getStr("email")?.trim()?.lowercase()
        if (email.isNullOrBlank()) throw CommonException(ErrorType.E111, "未获取到邮箱")
        if (claims.getStr("email_verified") != "true") throw CommonException(ErrorType.E111, "邮箱未通过 Google 验证")

        return GoogleIdentity(googleId = sub, email = email)
    }

    /** Google ID Token 校验后归一化得到的身份信息 */
    private data class GoogleIdentity(val googleId: String, val email: String)

    /**
     * 用授权码换 access_token,再拉取 GitHub 用户身份(数字 id + login + 已验证主邮箱)。
     * 出站经配置的 HTTP 代理(复用 google 代理),与 Google 校验同一出口。
     */
    private fun verifyGithubCode(params: GithubLoginParams): GithubIdentity {
        val clientId = projectConfig.githubClientId
        val clientSecret = projectConfig.githubClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) throw CommonException(ErrorType.E117, "服务端未配置 GitHub OAuth")
        if (params.code.isBlank()) throw CommonException(ErrorType.E117)

        // 1. code -> access_token（HuTool HTTPS POST 不经代理直连，改用 Java 11 HttpClient 保证走 clash）
        val accessToken: String = run {
            val builder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(8))
            if (projectConfig.googleProxyHost.isNotBlank() && projectConfig.googleProxyPort > 0) {
                builder.proxy(java.net.ProxySelector.of(
                    java.net.InetSocketAddress(projectConfig.googleProxyHost, projectConfig.googleProxyPort)))
            }
            val formBody = "client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
                "&client_secret=${java.net.URLEncoder.encode(clientSecret, "UTF-8")}" +
                "&code=${java.net.URLEncoder.encode(params.code, "UTF-8")}" +
                "&redirect_uri=${java.net.URLEncoder.encode(params.redirectUri, "UTF-8")}"
            val jReq = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://github.com/login/oauth/access_token"))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(formBody))
                .timeout(java.time.Duration.ofSeconds(8))
                .build()
            val resp = try { builder.build().send(jReq, java.net.http.HttpResponse.BodyHandlers.ofString()) }
            catch (e: Exception) { throw CommonException(ErrorType.E117, "无法连接 GitHub") }
            if (resp.statusCode() !in 200..299) throw CommonException(ErrorType.E117)
            JSONUtil.parseObj(resp.body()).getStr("access_token")
                ?: throw CommonException(ErrorType.E117, "未获取到 GitHub 令牌")
        }

        // 2. access_token -> 用户信息
        val userResp = runCatching {
            HttpUtil.createGet("https://api.github.com/user")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/vnd.github+json")
                .timeout(8000)
                .apply {
                    if (projectConfig.googleProxyHost.isNotBlank() && projectConfig.googleProxyPort > 0) {
                        setHttpProxy(projectConfig.googleProxyHost, projectConfig.googleProxyPort)
                    }
                }
                .execute()
        }.getOrElse { throw CommonException(ErrorType.E117, "无法获取 GitHub 用户信息") }
        if (!userResp.isOk) throw CommonException(ErrorType.E117)
        val userObj = JSONUtil.parseObj(userResp.body())
        val githubId = userObj.getLong("id")?.toString()
        val login = userObj.getStr("login")?.trim()
        if (githubId.isNullOrBlank() || login.isNullOrBlank()) throw CommonException(ErrorType.E117, "未获取到 GitHub 标识")

        // 3. 主邮箱(可能私密),取已验证的 primary;失败则视为无邮箱
        var email = userObj.getStr("email")?.trim()?.lowercase()
        if (email.isNullOrBlank()) {
            email = runCatching {
                val emailsResp = HttpUtil.createGet("https://api.github.com/user/emails")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/vnd.github+json")
                    .timeout(8000)
                    .apply {
                        if (projectConfig.googleProxyHost.isNotBlank() && projectConfig.googleProxyPort > 0) {
                            setHttpProxy(projectConfig.googleProxyHost, projectConfig.googleProxyPort)
                        }
                    }
                    .execute()
                if (!emailsResp.isOk) null
                else JSONUtil.parseArray(emailsResp.body())
                    .map { JSONUtil.parseObj(it) }
                    .firstOrNull { it.getBool("primary", false) && it.getBool("verified", false) }
                    ?.getStr("email")?.trim()?.lowercase()
            }.getOrNull()
        }

        if (email.isNullOrBlank()) throw CommonException(ErrorType.E117, "未获取到 GitHub 验证邮箱")
        return GithubIdentity(githubId = githubId, login = login, email = email)
    }

    /** GitHub 身份(email 已验证,非空,与 GoogleIdentity 结构对称) */
    private data class GithubIdentity(val githubId: String, val login: String, val email: String)

    override fun queryUserBacSetting(uid: String): BacSettingVO {
        return backSettingService.queryShowByUid(uid)
    }

    override fun queryUserSetting(uid: String): UserSetting = UserSetting(bacSetting = queryUserBacSetting(uid))

    override fun bacSetting(params: BackSettingParams, uid: String): BacSettingVO {
        val entity = backSettingService.ktQuery().eq(BackgroundConfigEntity::uid, uid).one()
            // 如果查询到了，则修改其中的参数
            // 如果没有查询到，则创建对象
            ?.also { it.updateParams(params) } ?: BackgroundConfigEntity(
            uid = uid, type = params.type
        ).also { it.setLinkId(params.backgroundId) }
        backSettingService.saveOrUpdate(entity)

        return backSettingService.queryShowByUid(uid)
    }

    override fun updateInfo(params: UserInfoUpdateParams): Boolean {
        if (params.nickName.isBlank()) return false
        return ktUpdate().eq(UserInfoEntity::id, BaseUtils.uid()).set(UserInfoEntity::nickName, params.nickName).update()
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
            .set(BackgroundConfigEntity::backgroundGradientId, entity.id)
            .set(BackgroundConfigEntity::backgroundImageId, null).update()
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
            this.backgroundImageId = bacImgEntity.id
            this.backgroundGradientId = null
        }.also { backSettingService.saveOrUpdate(it) }
        return file.currentName
    }

    override fun updateAvatar(multipartFile: MultipartFile, uid: String): String {
        val oldFileId = getById(uid)?.avatarFileId
        val file = fileService.updateAvatar(BaseUtils.uid(), multipartFile)
        ktUpdate().eq(UserInfoEntity::id, uid).set(UserInfoEntity::avatarFileId, file.id).update()
        // 新头像已关联成功后，再清理旧头像，避免旧文件永久孤立在存储桶中
        if (!oldFileId.isNullOrBlank() && oldFileId != file.id) {
            ossObjectService.findById(oldFileId)?.let {
                OssUtils.delete(it.objectKey)
                ossObjectService.markDeleted(it.id)
            }
        }
        return file.currentName
    }

    override fun del(params: UserDelParams): Boolean {
        val uid = BaseUtils.uid()
        val user = getById(uid) ?: throw CommonException(ErrorType.E215)
        // 已绑定邮箱的账号必须输入与绑定邮箱一致的邮箱才能注销；无邮箱账号(匿名/仅 Google)直接放行
        val boundEmail = user.email?.trim()
        if (!boundEmail.isNullOrEmpty()) {
            if (!boundEmail.equals(params.email?.trim(), ignoreCase = true)) {
                throw CommonException(ErrorType.E116)
            }
        }
        // 软删除并释放全部登录身份(邮箱/密码/Google/GitHub)。
        // 仅置 deleted=true 不够:登录入口按 email/google_id/github_id 查用户且不过滤 deleted,
        // 用同一身份重新登录会命中这条已注销记录,把旧数据"复活"。置空身份列后,
        // 重新登录无法命中旧记录、只会新建账户;同时释放 google_id 唯一索引,避免同一第三方账号重新注册时唯一约束冲突。
        val deleted = ktUpdate()
            .eq(UserInfoEntity::id, uid)
            .set(UserInfoEntity::deleted, true)
            .set(UserInfoEntity::email, null)
            .set(UserInfoEntity::password, null)
            .set(UserInfoEntity::googleId, null)
            .set(UserInfoEntity::googleEmail, null)
            .set(UserInfoEntity::githubId, null)
            .set(UserInfoEntity::githubLogin, null)
            .set(UserInfoEntity::avatarFileId, null)
            .update()
        if (deleted) {
            purgeUploadedFiles(uid, user.avatarFileId)
            // 注销成功后立即失效服务端会话，satoken 即时作废
            StpKit.USER.session.clear()
            StpKit.USER.logout()
        }
        return deleted
    }

    /**
     * 清理注销用户上传的全部文件（头像 + 自定义背景图）。
     *
     * 软删除只是让账号登不上去，OSS 里的字节还在、key 还可推导。头像和背景图是用户主动上传的
     * **真数据**，`docs/oss-architecture.md` §2 定的规矩是它们必须随 DB 行同步删除 —— 换头像
     * （[updateAvatar]）和删背景图（[BackgroundImageServiceImpl.deleteUserImage]）一直是这么做的，
     * 唯独注销这条路径漏了，于是注销后对象永久留在桶里。
     *
     * 顺序与 [BackgroundImageServiceImpl.deleteUserImage] 保持一致：**先删库行，再删 OSS 对象**。
     * 反过来一旦库删除失败，就会留下一条指向已消失对象的记录。OSS 删除失败只记警告
     * （[OssUtils.delete] 本身吞异常），残留对象由桶的生命周期规则兜底 —— 用户视角文件已经没了。
     *
     * `isDefault = true` 的背景图是系统预置、多用户共享的，只解除关联绝不碰对象。
     *
     * 整体包在 [runCatching] 里：清理失败不该让"账号已注销"这个已经生效的结果回滚 ——
     * 用户按下注销按钮拿到失败提示、账号却已经没了是更糟的结果。漏掉的对象由对账任务兜底。
     */
    private fun purgeUploadedFiles(uid: String, avatarFileId: String?) {
        runCatching {
            val bacImages = bacImageService.ktQuery()
                .eq(BackgroundImageEntity::uid, uid)
                .eq(BackgroundImageEntity::isDefault, false)
                .list()
            val fileIds = (bacImages.map { it.fileId } + listOfNotNull(avatarFileId))
                .filter { it.isNotBlank() }
                .distinct()
            if (fileIds.isEmpty()) return@runCatching

            val objects = ossObjectService.findByIds(fileIds).values

            if (bacImages.isNotEmpty()) bacImageService.removeByIds(bacImages.map { it.id })
            objects.forEach {
                OssUtils.delete(it.objectKey)
                ossObjectService.markDeleted(it.id)
            }

            logger.info("[del] 已清理注销用户的上传文件: uid={}, files={}, objects={}", uid, fileIds.size, objects.size)
        }.onFailure {
            logger.warn("[del] 注销用户文件清理失败(忽略): uid={}, err={}", uid, it.message)
        }
    }

    override fun updateUsername(username: String): Boolean =
        ktUpdate().eq(UserInfoEntity::id, BaseUtils.uid()).set(UserInfoEntity::nickName, username).update()

    // 旧的手机号绑定/改绑实现绕过验证码，已随手机号功能一并删除。绑定/改绑统一走 verifyEmail 的验证码校验路径。

    override fun adminListAll(params: UserSearchParams): IPage<UserAdminVO> {
        val entityPage = baseMapper.selectPage(params.toPage(), params.toWrapper())
        val users = entityPage.records.toList()
        val page = entityPage.convert { UserAdminVO(it) }
        // 头像签名要查 oss_object 账本。账本里查不到(旧数据/已清理)时留 null，
        // 前端退回首字母色块 —— 不该因为一张头像让整个用户列表 500
        runCatching {
            val avatars = adminUserViewAssembler.avatarUrls(users)
            page.records.forEach { vo -> vo.avatarUrl = avatars[vo.id] }
        }.onFailure { logger.warn("[adminListAll] 用户头像签名失败(忽略): {}", it.message) }
        return page
    }
}
