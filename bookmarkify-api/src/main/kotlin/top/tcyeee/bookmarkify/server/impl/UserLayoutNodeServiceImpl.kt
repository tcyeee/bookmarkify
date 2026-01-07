package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import top.tcyeee.bookmarkify.mapper.UserLayoutNodeMapper
import top.tcyeee.bookmarkify.server.IUserLayoutNodeService

/**
 * 用户桌面排布节点 Service 实现
 *
 * @author tcyeee
 * @date 1/7/26
 */
@Service
class UserLayoutNodeServiceImpl :
    IUserLayoutNodeService,
    ServiceImpl<UserLayoutNodeMapper, UserLayoutNodeEntity>()

