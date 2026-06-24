package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink

/**
 * @author tcyeee
 * @date 3/10/24 22:19
 */
@Mapper
interface BookmarkUserLinkMapper : BaseMapper<BookmarkUserLink> {

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
               b.is_activity                                AS isActivity,
               b.url_host                                   AS urlHost,
               b.app_name                                   AS appName,
               wl.icon_base64                               AS iconBase64,
               COALESCE(wl.icon_padding, 25)                AS iconPadding,
               wl.icon_bg_color                             AS iconBgColor,
               COALESCE(wl.use_hd_logo, FALSE)              AS useHdLogo,
               CASE WHEN wl.height >= 150 THEN wl.height ELSE 0 END AS hdSize
            FROM bookmark_user_link a
                     LEFT JOIN bookmark b
                               ON a.bookmark_id = b.id
                     LEFT JOIN LATERAL (
                SELECT w.icon_base64, w.icon_padding, w.icon_bg_color, w.use_hd_logo, w.height
                FROM website_logo w
                WHERE w.bookmark_id = a.bookmark_id
                ORDER BY (w.icon_base64 IS NOT NULL) DESC, w.height DESC
                LIMIT 1
                ) wl ON TRUE
            where a.uid = #{uid}
            """
    )
    @Description("查看用户的全部书签信息")
    fun allBookmarkByUid(uid: String): List<BookmarkShow>

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
               b.is_activity                                AS isActivity,
               b.url_host                                   AS urlHost,
               b.app_name                                   AS appName,
               wl.icon_base64                               AS iconBase64,
               COALESCE(wl.icon_padding, 25)                AS iconPadding,
               wl.icon_bg_color                             AS iconBgColor,
               COALESCE(wl.use_hd_logo, FALSE)              AS useHdLogo,
               CASE WHEN wl.height >= 180 THEN wl.height ELSE 0 END AS hdSize
            FROM bookmark_user_link a
                     LEFT JOIN bookmark b
                               ON a.bookmark_id = b.id
                     LEFT JOIN LATERAL (
                SELECT w.icon_base64, w.icon_padding, w.icon_bg_color, w.use_hd_logo, w.height
                FROM website_logo w
                WHERE w.bookmark_id = a.bookmark_id
                ORDER BY (w.icon_base64 IS NOT NULL) DESC, w.height DESC
                LIMIT 1
                ) wl ON TRUE
            where a.id = #{id}
            limit 1
            """
    )
    @Description("查看用户的单个书签")
    fun findShowById(id: String): BookmarkShow
}