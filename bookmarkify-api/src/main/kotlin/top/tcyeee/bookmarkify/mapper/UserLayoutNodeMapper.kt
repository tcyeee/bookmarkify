package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity

/**
 * 用户桌面排布节点 Mapper
 *
 * @author tcyeee
 * @date 1/7/26
 */
@Mapper
interface UserLayoutNodeMapper : BaseMapper<UserLayoutNodeEntity>

