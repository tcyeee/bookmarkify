package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.UserBackgroundLinkEntity

/**
 * 用户背景关联 Service
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
interface IUserBackgroundLinkService : IService<UserBackgroundLinkEntity> {
    fun queryByUid(uid: String): UserBackgroundLinkEntity
}
