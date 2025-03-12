package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import top.tcyeee.bookmarkify.entity.po.BookmarkTag

/**
 * @author tcyeee
 * @date 3/11/24 17:36
 */
@Mapper
interface BookmarkTagMapper : BaseMapper<BookmarkTag> {
    @Select(
        """
            select *
            from bookmarkify.bookmark_tag a
            where a.uid = #{uid}
            and a.deleted is false
            """
    )
    fun findAllByUid(uid: String): List<BookmarkTag>
}