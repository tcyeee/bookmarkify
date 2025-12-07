package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.UserBackgroundLinkEntity

/**
 * 用户背景关联表 Mapper
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Mapper
interface UserBackgroundLinkMapper : BaseMapper<UserBackgroundLinkEntity>
