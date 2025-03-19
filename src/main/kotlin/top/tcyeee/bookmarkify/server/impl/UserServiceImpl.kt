package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.po.UserEntity
import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
import top.tcyeee.bookmarkify.entity.request.UserInfoUptateParams
import top.tcyeee.bookmarkify.entity.response.UserInfoShow
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 3/11/25 20:02
 */
@Service
class UserServiceImpl : IUserService, ServiceImpl<UserMapper, UserEntity>() {

    override fun getByDeviceInfo(form: LoginByClientForm): UserEntity? {
        return ktQuery().eq(UserEntity::deviceUid, form.deviceUid).or().one()
    }

    override fun createUserByDevicInfo(form: LoginByClientForm): UserEntity {
        val one: UserEntity? = this.getByDeviceInfo(form)
        if (one != null) throw CommonException(ErrorType.E101)

        val userEntity = UserEntity(form)
        this.save(userEntity)
        return userEntity
    }

    override fun updateDeviceUidOrFingerprint(uid: String, form: LoginByClientForm) {
        ktUpdate().eq(UserEntity::uid, uid)
            .set(UserEntity::deviceUid, form.deviceUid)
            .set(UserEntity::updateTime, LocalDateTime.now()).update()
    }

    override fun userInfo(): UserInfoShow {
        return UserInfoShow(getById(BaseUtils.uid()))
    }

    override fun updateInfo(params: UserInfoUptateParams): Boolean {
        if (params.nickName.isBlank()) return false
        return ktUpdate()
            .eq(UserEntity::uid, BaseUtils.uid())
            .set(UserEntity::nickName, params.nickName)
            .update()
    }

    override fun updateUsername(username: String): Boolean {
        return ktUpdate().eq(UserEntity::uid, BaseUtils.uid()).set(UserEntity::nickName, username).update()
    }

    override fun changePhone(phone: String): Boolean {
        return ktUpdate().eq(UserEntity::uid, BaseUtils.uid()).set(UserEntity::phone, phone).update()
    }

    override fun checkPhone(code: Int): Boolean {
        return ktUpdate().eq(UserEntity::uid, BaseUtils.uid()).set(UserEntity::email, code).update()
    }

    override fun changeMail(mail: String): Boolean {
        return ktUpdate().eq(UserEntity::uid, BaseUtils.uid()).set(UserEntity::email, mail).update()
    }

    override fun del(): Boolean {
        return ktUpdate().eq(UserEntity::uid, BaseUtils.uid()).set(UserEntity::deleted, true).update()
    }
}