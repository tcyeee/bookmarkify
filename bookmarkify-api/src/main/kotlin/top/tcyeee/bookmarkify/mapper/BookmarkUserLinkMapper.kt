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
               a.id                                         AS bookmarkUserLinkId,
               a.url_full                                   AS urlFull,
               CONCAT(b.url_scheme,'://', b.url_host, b.url_path) AS urlBase,
               COALESCE(a.title, b.title)                   AS title,
               COALESCE(a.description, b.description)       AS description,
               b.is_activity                                AS isActivity,
               b.icon_base64                                AS iconBase64,
               b.url_host                                   AS urlHost,
               b.app_name                                   AS appName,
               c.height                                     AS hdSize
            FROM bookmarkify.bookmark_user_link a
                     LEFT JOIN bookmarkify.bookmark b
                               ON a.bookmark_id = b.id
                     LEFT JOIN LATERAL (
                SELECT wl.height
                FROM bookmarkify.website_logo wl
                WHERE wl.bookmark_id = a.bookmark_id
                  AND wl.is_og_img IS FALSE
                  AND wl.height >= 150
                ORDER BY wl.height
                LIMIT 1
                ) c ON TRUE
            where a.uid = #{uid}
            """
    )
    @Description("查看用户的全部书签信息")
    fun allBookmarkByUid(uid: String): List<BookmarkShow>

    @Select(
        """
            SELECT a.bookmark_id                            AS bookmarkId,
               a.id                                         AS bookmarkUserLinkId,
               a.url_full                                   AS urlFull,
               CONCAT(b.url_scheme,'://', b.url_host, b.url_path) AS urlBase,
               COALESCE(a.title, b.title)                   AS title,
               COALESCE(a.description, b.description)       AS description,
               b.is_activity                                AS isActivity,
               b.icon_base64                                AS iconBase64,
               b.url_host                                   AS urlHost,
               b.app_name                                   AS appName,
               c.height                                     AS hdSize
            FROM bookmarkify.bookmark_user_link a
                     LEFT JOIN bookmarkify.bookmark b
                               ON a.bookmark_id = b.id
                     LEFT JOIN LATERAL (
                SELECT wl.height
                FROM bookmarkify.website_logo wl
                WHERE wl.bookmark_id = a.bookmark_id
                  AND wl.is_og_img IS FALSE
                  AND wl.height >= 180
                ORDER BY wl.height
                LIMIT 1
                ) c ON TRUE
            where a.id = #{id}
            limit 1
            """
    )
    @Description("查看用户的单个书签")
    fun findOne(id: String): BookmarkShow
}