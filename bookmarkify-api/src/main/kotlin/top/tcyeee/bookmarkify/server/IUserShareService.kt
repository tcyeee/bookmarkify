package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.ShareCreateParams
import top.tcyeee.bookmarkify.entity.ShareSearchParams
import top.tcyeee.bookmarkify.entity.SharePublicVO
import top.tcyeee.bookmarkify.entity.ShareUpdateParams
import top.tcyeee.bookmarkify.entity.UserShareAdminVO
import top.tcyeee.bookmarkify.entity.UserShareVO
import top.tcyeee.bookmarkify.entity.entity.UserShareEntity

/**
 * @author tcyeee
 */
interface IUserShareService : IService<UserShareEntity> {
    /** 创建并发布一个分享 */
    fun createShare(params: ShareCreateParams, uid: String): UserShareVO
    /** 通过分享ID查看公开的分享内容；不存在/已下架/已过期均抛出异常 */
    fun viewByCode(id: String): SharePublicVO
    /** 分页查看自己发布的全部分享 */
    fun listMine(uid: String, pageBean: PageBean): IPage<UserShareVO>
    /** 下架自己发布的分享；非本人的分享或已处于非正常状态的分享将抛出异常 */
    fun cancelShare(id: String, uid: String): Boolean
    /** 修改自己发布的分享(文案/过期时间)；非本人的分享或已处于非正常状态的分享将抛出异常 */
    fun updateShare(params: ShareUpdateParams, uid: String): UserShareVO
    /** 管理端：分页查询全部分享 */
    fun adminListAll(params: ShareSearchParams): IPage<UserShareAdminVO>
    /** 管理端：强制下架某个分享 */
    fun adminTakeDown(id: String): Boolean
}
