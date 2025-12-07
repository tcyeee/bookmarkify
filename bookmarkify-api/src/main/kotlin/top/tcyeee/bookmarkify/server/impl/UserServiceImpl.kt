package top.tcyeee.bookmarkify.server.impl

import cn.dev33.satoken.stp.StpUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.BacSettingVO
import top.tcyeee.bookmarkify.entity.common.GradientConfig
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.entity.entity.BackgroundImageEntity
import top.tcyeee.bookmarkify.entity.entity.BackgroundType
import top.tcyeee.bookmarkify.entity.entity.UserBackgroundLinkEntity
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.BackSettingParams
import top.tcyeee.bookmarkify.entity.UserDelParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.UserInfoUptateParams
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
    private val backSettingService: UserBackgroundLinkServiceImpl,
    private val bacGradientService: BackgroundGradientServiceImpl,
    private val bacImageService: BackgroundImageServiceImpl,
    private val fileMapper: FileMapper,
    private val fileService: FileServiceImpl,
) : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    override fun createUserByDeviceId(deviceId: String): UserEntity {
        val userEntity = UserEntity(deviceId)
        this.save(userEntity)
        return userEntity
    }

    override fun queryUserBacSetting(uid: String): BacSettingVO {
        backSettingService.queryByUid(uid)

        TODO("Not yet implemented")
    }

    override fun bacSetting(params: BackSettingParams, uid: String): Boolean {
        val entity = backSettingService.ktQuery()
            .eq(UserBackgroundLinkEntity::uid, uid)
            .one()
            // 如果查询到了，则修改其中的参数
            ?.also { it.updateParams(params) }
        // 如果没有查询到，则创建对象
            ?: UserBackgroundLinkEntity(uid = uid, type = params.type, backgroundLinkId = params.backgroundId)
        backSettingService.saveOrUpdate(entity)
        return true
    }

    override fun userInfo(): UserInfoShow {
        val userEntity = getById(BaseUtils.uid()) ?: throw CommonException(ErrorType.E202)
        return UserInfoShow(userEntity, StpUtil.getTokenValue())
            .apply { avatar = fileMapper.selectById((userEntity.avatarFileId)) }
    }

    override fun updateInfo(params: UserInfoUptateParams): Boolean {
        if (params.nickName.isBlank()) return false
        return ktUpdate()
            .eq(UserEntity::id, BaseUtils.uid())
            .set(UserEntity::nickName, params.nickName)
            .update()
    }

    override fun updateBacColor(config: GradientConfig, uid: String): Boolean {
        val entity = BackgroundGradientEntity(
            uid = uid,
            gradient = JSONUtil.toJsonStr(config.colors),
            direction = config.direction,
        ).also { bacGradientService.save(it) }

        backSettingService.ktUpdate()
            .eq(UserBackgroundLinkEntity::uid, uid)
            .set(UserBackgroundLinkEntity::type, BackgroundType.GRADIENT)
            .set(UserBackgroundLinkEntity::backgroundLinkId, entity.id)
            .update()
        return true
    }

    override fun updateBacImg(file: MultipartFile, uid: String): String {
        val file = fileService.uploadBackground(BaseUtils.uid(), file)

        // 添加到背景图片数据库
        val bacImgEntity = BackgroundImageEntity(uid = uid, fileId = file.id).also { bacImageService.save(it) }

        // 修改用户背景图片设置
        UserBackgroundLinkEntity(uid = uid, type = BackgroundType.IMAGE, backgroundLinkId = bacImgEntity.id)
            .also { backSettingService.save(it) }

        return file.currentName
    }

    override fun updateAvatar(file: MultipartFile, uid: String): String {
        val file = fileService.updateAvatar(BaseUtils.uid(), file)

        ktUpdate()
            .eq(UserEntity::id, uid)
            .set(UserEntity::avatarFileId, file.id)
            .update()

        return file.currentName
    }

    override fun getByDeviceId(deviceId: String): UserEntity? =
        ktQuery().eq(UserEntity::deviceId, deviceId).one()

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