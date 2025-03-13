package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import top.tcyeee.bookmarkify.entity.po.Bookmark
import top.tcyeee.bookmarkify.entity.response.BookmarkShow

/**
 * @author tcyeee
 * @date 3/10/24 15:47
 */
@Mapper
interface BookmarkMapper : BaseMapper<Bookmark> {
    @Select(
        """
            select a.uid           as uid,
                   b.id            as bookmarkId,
                   a.id            as bookmarkUserLinkId,
                   a.title         as userTitle,
                   a.description   as userDescription,
                   b.title         as baseTitle,
                   b.description   as baseDescription,
                   b.is_activity   as isActivity,
                   b.icon_activity as iconActivity,
                   b.icon_hd       as iconHd,
                   b.icon_url      as iconUrl,
                   a.url_full      as urlFull
            from bookmark_user_link a
                     left join bookmark b on a.bookmark_id = b.id
            where a.uid = #{uid}
            """
    )
    fun findShowByUid(uid: String): List<BookmarkShow>
}