package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.Bookmark

/**
 * @author tcyeee
 * @date 3/10/24 15:47
 */
@Mapper
interface BookmarkMapper : BaseMapper<Bookmark>