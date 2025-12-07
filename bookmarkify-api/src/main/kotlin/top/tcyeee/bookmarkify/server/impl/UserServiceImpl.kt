package top.tcyeee.bookmarkify.server.impl

import cn.dev33.satoken.stp.StpUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.common.BackgroundConfig
import top.tcyeee.bookmarkify.entity.entity.UserBackgroundLinkEntity
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.request.UpdateBackgroundParams
import top.tcyeee.bookmarkify.entity.request.UserDelParams
import top.tcyeee.bookmarkify.entity.request.UserInfoUptateParams
import top.tcyeee.bookmarkify.entity.response.UserInfoShow
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
    private val userBackgroundLinkService: UserBackgroundLinkServiceImpl,
    private val fileMapper: FileMapper
) : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    override fun getByDeviceId(deviceId: String): UserEntity? {
        return ktQuery().eq(UserEntity::deviceId, deviceId).one()
    }

    override fun createUserByDeviceId(deviceId: String): UserEntity {
        val userEntity = UserEntity(deviceId)
        this.save(userEntity)
        return userEntity
    }

    override fun updateBackground(params: UpdateBackgroundParams, uid: String): Boolean {
        val entity = userBackgroundLinkService.ktQuery()
            .eq(UserBackgroundLinkEntity::uid, uid)
            .one()
            // 如果查询到了，则修改其中的参数
            ?.also { it.updateParams(params) }
        // 如果没有查询到，则创建对象
            ?: UserBackgroundLinkEntity(uid = uid, type = params.type, backgroundLinkId = params.backgroundId)
        userBackgroundLinkService.saveOrUpdate(entity)

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

    override fun updateUsername(username: String): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::nickName, username).update()
    }

    override fun changePhone(phone: String): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::phone, phone).update()
    }

    override fun checkPhone(code: Int): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::email, code).update()
    }

    override fun changeMail(mail: String): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).set(UserEntity::email, mail).update()
    }

    override fun updateBackgroundConfig(config: BackgroundConfig): Boolean {
        val json = JSONUtil.toJsonStr(config)
        return ktUpdate()
            .eq(UserEntity::id, BaseUtils.uid())
//            .set(UserEntity::backgroundConfigJson, json)
            .update()
    }

    override fun del(params: UserDelParams): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid()).eq(UserEntity::password, pwd(params.password))
            .set(UserEntity::deleted, true).update()
    }
}