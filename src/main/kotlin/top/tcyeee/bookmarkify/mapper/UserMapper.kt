package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.po.UserEntity

/**
 * @author tcyeee
 * @date 2/13/25 13:05
 */
@Mapper
interface UserMapper : BaseMapper<UserEntity>