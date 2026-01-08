package top.tcyeee.bookmarkify.config.filter

import cn.dev33.satoken.stp.StpInterface
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.mapper.UserMapper

/**
 * @author tcyeee
 * @date 1/8/26 18:27
 */
@Component
class StpInterfaceImpl(
    private val userMapper: UserMapper
) : StpInterface {
    override fun getRoleList(loginId: Any, loginType: String?): List<String> =
        userMapper.selectById(loginId.toString())?.let { listOf(it.role.name) } ?: emptyList()

    override fun getPermissionList(loginId: Any, loginType: String?): List<String> = emptyList()
}