package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.po.UserEntity
import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IUserService
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 3/11/25 20:02
 */
@Service
class UserServiceImpl : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    override fun getByDeviceInfo(form: LoginByClientForm): UserEntity? {
        return ktQuery().eq(UserEntity::deviceUid, form.deviceUid).or().eq(UserEntity::fingerPrint, form.fingerprint)
            .one()
    }

    override fun createUserByDevicInfo(form: LoginByClientForm): UserEntity {
        val one: UserEntity? = this.getByDeviceInfo(form)
        if (one != null) throw CommonException(ErrorType.E101)

        val userEntity = UserEntity(form)
        this.save(userEntity)
        return userEntity
    }

    override fun updateDeviceUidOrFingerprint(uid: String, form: LoginByClientForm) {
        ktUpdate().eq(UserEntity::uid, uid).set(UserEntity::fingerPrint, form.fingerprint)
            .set(UserEntity::deviceUid, form.deviceUid).set(UserEntity::updateTime, LocalDateTime.now()).update()
    }
}