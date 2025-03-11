package top.tcyeee.bookmarkify.server.impl

import cn.dev33.satoken.stp.StpUtil
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.po.UserEntity
import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
import top.tcyeee.bookmarkify.entity.response.UserEntityVo
import top.tcyeee.bookmarkify.server.IUserLoginService
import top.tcyeee.bookmarkify.server.IUserService

/**
 * @author tcyeee
 * @date 2/12/25 21:46
 */
@Service
class UserLoginServiceImpl(
    private var userService: IUserService
) : IUserLoginService {

    override fun loginByClientInfo(form: LoginByClientForm): UserEntityVo {
        var user: UserEntity? = userService.getByDeviceInfo(form)
        if (user == null) user = userService.createUserByDevicInfo(form)

        // 如果设备信息变动则更新
        if (user.checkDeviceInfo(form)) userService.updateDeviceUidOrFingerprint(user.uid, form)

        // 注册会话
        StpUtil.login(user.uid)
        return UserEntityVo(user, StpUtil.getTokenValue())
    }
}