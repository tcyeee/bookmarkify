package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.entity.UserShareBookmarkEntity

/**
 * @author tcyeee
 */
@Mapper
interface UserShareBookmarkMapper : BaseMapper<UserShareBookmarkEntity> {

    @Select(
        """
            SELECT a.bookmark_id                            AS bookmarkId,
               a.uid                                        AS uid,
               a.id                                         AS bookmarkUserLinkId,
               a.url_full                                   AS urlFull,
               a.layout_node_id                             AS layoutNodeId,
               CONCAT(b.url_scheme,'://', b.url_host) AS urlBase,
               COALESCE(a.title, b.title)                   AS title,
               COALESCE(a.description, b.description)       AS description,
               a.pinned                                     AS pinned,
               a.link_type                                  AS linkType,
               b.is_activity                                AS isActivity,
               b.url_host                                   AS urlHost,
               b.app_name                                   AS appName,
               b.nsfw                                        AS nsfw
            FROM user_share_bookmark s
                     JOIN bookmark_user_link a
                          ON a.id = s.bookmark_user_link_id
                     LEFT JOIN bookmark b
                               ON a.bookmark_id = b.id
            WHERE s.share_id = #{shareId}
            ORDER BY s.sort
            """
    )
    @Description("查看某个分享包含的全部书签信息")
    fun bookmarksByShareId(shareId: String): List<BookmarkShow>
}
