package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.UserBackgroundLinkEntity
import top.tcyeee.bookmarkify.mapper.UserBackgroundLinkMapper
import top.tcyeee.bookmarkify.server.IUserBackgroundLinkService

/**
 * 用户背景关联 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class UserBackgroundLinkServiceImpl : IUserBackgroundLinkService,
    ServiceImpl<UserBackgroundLinkMapper, UserBackgroundLinkEntity>()
