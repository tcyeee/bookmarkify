package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
import top.tcyeee.bookmarkify.entity.response.UserAuthEntityVo

/**
 * 用户登陆相关
 *
 * @author tcyeee
 * @date 2/15/25 00:14
 */
interface IUserLoginService {

    /**
     * 用户登陆流程
     *
     * @param form 浏览器指纹
     * @return userInfo
     */
    fun loginByClientInfo(form: LoginByClientForm): UserAuthEntityVo
}