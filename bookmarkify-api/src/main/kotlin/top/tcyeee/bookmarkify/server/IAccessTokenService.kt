package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.AccessTokenCreateParams
import top.tcyeee.bookmarkify.entity.AccessTokenCreatedVO
import top.tcyeee.bookmarkify.entity.AccessTokenVO
import top.tcyeee.bookmarkify.entity.entity.AccessTokenEntity

/**
 * 浏览器插件访问令牌：与 Sa-Token 登录会话完全独立，详见根目录 ACCESS_TOKEN_DESIGN.md。
 *
 * @author tcyeee
 */
interface IAccessTokenService : IService<AccessTokenEntity> {
    /** 生成一个新令牌；返回值含明文 token，仅此一次 */
    fun create(params: AccessTokenCreateParams, uid: String): AccessTokenCreatedVO
    /** 查看自己名下的全部令牌(不含明文) */
    fun listMine(uid: String): List<AccessTokenVO>
    /** 撤销自己名下的令牌；非本人的令牌将抛出异常 */
    fun revoke(id: String, uid: String): Boolean
    /** 校验插件请求带来的明文 token，返回其所属 uid；无效/已撤销返回 null。校验通过会异步更新 lastUsedAt */
    fun verify(rawToken: String): String?
    /** 供 /extension/ping 使用：校验通过后返回令牌自身信息(不含明文)，供第三方在正式接入前自检配置是否正确；无效/已撤销返回 null */
    fun pingInfo(rawToken: String): AccessTokenVO?
}
