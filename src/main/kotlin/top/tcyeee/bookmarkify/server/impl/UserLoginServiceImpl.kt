package top.tcyeee.bookmarkify.server.impl

import cn.dev33.satoken.stp.StpUtil
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.dto.UserInfo
import top.tcyeee.bookmarkify.entity.po.UserEntity
import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
import top.tcyeee.bookmarkify.entity.response.UserAuthEntityVo
import top.tcyeee.bookmarkify.server.IUserLoginService
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * @author tcyeee
 * @date 2/12/25 21:46
 */
@Service
class UserLoginServiceImpl(
    private var userService: IUserService
) : IUserLoginService {
    override fun loginByClientInfo(form: LoginByClientForm): UserAuthEntityVo {
        // 如果用户已经登录则直接返回
        if (StpUtil.isLogin()) return UserAuthEntityVo(BaseUtils.user(), StpUtil.getTokenValue())

        var user: UserEntity? = userService.getByDeviceInfo(form)
        if (user == null) user = userService.createUserByDevicInfo(form)

        // 如果设备信息变动则更新
        if (user.checkDeviceInfo(form)) userService.updateDeviceUidOrFingerprint(user.uid, form)

        // 注册会话
        StpUtil.login(user.uid)
        StpUtil.getSession().set("user", UserInfo(user).json())
        return UserAuthEntityVo(user, StpUtil.getTokenValue())
    }
}