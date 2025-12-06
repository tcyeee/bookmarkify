package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.request.UserDelParams
import top.tcyeee.bookmarkify.entity.request.UserInfoUptateParams
import top.tcyeee.bookmarkify.entity.response.UserInfoShow
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * @author tcyeee
 * @date 3/11/25 20:02
 */
@Service
class UserServiceImpl : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    override fun getByDeviceId(deviceId: String): UserEntity? {
        return ktQuery()
            .eq(UserEntity::deviceId, deviceId)
            .one()
    }

    override fun createUserByDeviceId(deviceId: String): UserEntity {
        val userEntity = UserEntity(deviceId)
        this.save(userEntity)
        return userEntity
    }

    override fun userInfo(): UserInfoShow {
        return UserInfoShow(getById(BaseUtils.uid()))
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

    override fun del(params: UserDelParams): Boolean {
        return ktUpdate().eq(UserEntity::id, BaseUtils.uid())
            .eq(UserEntity::password, BaseUtils.pwd(params.password))
            .set(UserEntity::deleted, true).update()
    }
}