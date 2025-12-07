package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.UserAuthEntityVo

/**
 * 用户登陆相关
 *
 * @author tcyeee
 * @date 2/15/25 00:14
 */
interface IUserLoginService {
    fun loginByDeviceId(deviceId: String): UserAuthEntityVo
}