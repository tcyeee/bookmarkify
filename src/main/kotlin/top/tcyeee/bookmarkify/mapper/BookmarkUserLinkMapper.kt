package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.po.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.response.BookmarkShow

/**
 * @author tcyeee
 * @date 3/10/24 22:19
 */
@Mapper
interface BookmarkUserLinkMapper : BaseMapper<BookmarkUserLink>{

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
                   a.url_full      as urlFull,
                   b.icon_url      as iconUrl
            from bookmarkify.bookmark_user_link a
                     left join bookmarkify.bookmark b on a.bookmark_id = b.id
            where a.uid = #{uid}
            """
    )
    @Description("查看用户的全部书签信息")
    fun allBookmarkByUid(uid: String): List<BookmarkShow>

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
                   a.url_full      as urlFull
            from bookmarkify.bookmark_user_link a
                     left join bookmarkify.bookmark b on a.bookmark_id = b.id
            where a.id = #{id}
            """
    )
    @Description("查看用户的单个书签")
    fun findOne(id: String): BookmarkShow
}