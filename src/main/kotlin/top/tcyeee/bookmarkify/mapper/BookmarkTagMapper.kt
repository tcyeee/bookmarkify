package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.po.BookmarkTag

/**
 * @author tcyeee
 * @date 3/11/24 17:36
 */
@Mapper
interface BookmarkTagMapper : BaseMapper<BookmarkTag>