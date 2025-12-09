package top.tcyeee.bookmarkify.server.impl

import cn.dev33.satoken.session.SaSession
import cn.dev33.satoken.stp.StpUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.*
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.dto.UserSetting
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils
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
) : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    /**
     * 获取用户信息
     * @param uid uid
     * @return 用户基础信息 + 头像 + 设置 （没有TOKEN）
     */
    override fun userInfo(uid: String): UserInfoShow = getById(uid)?.vo()?.apply {
        avatar = fileMapper.selectById(this.avatarFileId)
        userSetting = queryUserSetting(uid)
    } ?: throw CommonException(ErrorType.E202)

    /**
     * 注册用户信息
     * @param request   request
     * @param response  response
     * @return 用户基础信息+token (注意：这里不包含用户头像和用户设置)
     */
    override fun track(request: HttpServletRequest, response: HttpServletResponse): UserSessionInfo =
        if (StpUtil.isLogin() && BaseUtils.user() != null) {
            BaseUtils.user()!!
        } else {
            BaseUtils.registerDeviceId(request, response, projectConfig)
                .let(this::queryOrRegisterByDeviceId)
                .also { StpUtil.login(it.id, true) }
                .authVO(StpUtil.getTokenValue())
                .writeToSession()
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

    override fun queryUserSetting(uid: String): UserSetting = UserSetting(
        bacSetting = queryUserBacSetting(uid)
    )

    override fun bacSetting(params: BackSettingParams, uid: String): Boolean {
        val entity = backSettingService.ktQuery().eq(BackgroundConfigEntity::uid, uid).one()
            // 如果查询到了，则修改其中的参数
            ?.also { it.updateParams(params) }
        // 如果没有查询到，则创建对象
            ?: BackgroundConfigEntity(uid = uid, type = params.type, backgroundLinkId = params.backgroundId)
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

    override fun addBacImg(file: MultipartFile, uid: String): String {
        val file = fileService.uploadBackground(BaseUtils.uid(), file)

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

    override fun updateAvatar(file: MultipartFile, uid: String): String {
        val file = fileService.updateAvatar(BaseUtils.uid(), file)

        ktUpdate().eq(UserEntity::id, uid).set(UserEntity::avatarFileId, file.id).update()

        return file.currentName
    }

    override fun del(params: UserDelParams): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).eq(UserEntity::password, pwd(params.password))
            .set(UserEntity::deleted, true).update()

    override fun updateUsername(username: String): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::nickName, username).update()

    override fun changePhone(phone: String): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::phone, phone).update()

    override fun checkPhone(code: Int): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::email, code).update()

    override fun changeMail(mail: String): Boolean =
        ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::email, mail).update()
}