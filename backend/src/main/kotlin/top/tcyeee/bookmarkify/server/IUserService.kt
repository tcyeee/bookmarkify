package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
import top.tcyeee.bookmarkify.entity.request.UserDelParams
import top.tcyeee.bookmarkify.entity.request.UserInfoUptateParams
import top.tcyeee.bookmarkify.entity.response.UserInfoShow

/**
 * @author tcyeee
 * @date 3/11/25 20:01
 */
interface IUserService : IService<UserEntity> {

    /**
     * 通过谷歌ID/浏览器缓存ID/浏览器指纹ID 来寻找用户
     * 这三个根据变动概率来进行优先级排序
     *
     * @return 用户信息
     */
    fun getByDeviceInfo(form: LoginByClientForm): UserEntity?

    fun createUserByDevicInfo(form: LoginByClientForm): UserEntity

    fun updateDeviceUidOrFingerprint(uid: String, form: LoginByClientForm)

    fun userInfo(): UserInfoShow
    fun updateInfo(params: UserInfoUptateParams): Boolean
    fun updateUsername(username: String): Boolean
    fun changePhone(phone: String): Boolean
    fun checkPhone(code: Int): Boolean
    fun changeMail(mail: String): Boolean
    fun del(params: UserDelParams): Boolean
}