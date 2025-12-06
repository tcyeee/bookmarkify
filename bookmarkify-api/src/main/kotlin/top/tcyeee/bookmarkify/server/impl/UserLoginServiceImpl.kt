package top.tcyeee.bookmarkify.server.impl

import cn.dev33.satoken.stp.StpUtil
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.entity.UserEntity
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

    override fun loginByDeviceId(deviceId: String): UserAuthEntityVo {
        var user: UserEntity? = userService.getByDeviceId(deviceId)
        if (user == null) user = userService.createUserByDeviceId(deviceId)

        val userSessionInfo = UserSessionInfo(user)
        return registerSession(userSessionInfo)
    }

    // 注册会话
    private fun registerSession(user: UserSessionInfo): UserAuthEntityVo {
        StpUtil.login(user.uid,true)
        StpUtil.getSession().set("user",BaseUtils.userToJson(user))
        return UserAuthEntityVo(user, StpUtil.getTokenValue())
    }
}