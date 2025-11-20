package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.HomeItem

/**
 * @author tcyeee
 * @date 4/14/24 15:39
 */
@Mapper
interface HomeItemMapper : BaseMapper<HomeItem>