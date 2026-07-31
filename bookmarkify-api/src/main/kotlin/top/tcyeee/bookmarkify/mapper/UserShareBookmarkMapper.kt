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
               -- 三层的标题分开带上来，最终显示哪个由 BookmarkDisplayPolicy 决定。
               -- 此前这里是 COALESCE(a.title, b.title) AS title，把「用户改过」和
               -- 「没改过」压成了同一个值，优先级也就无从表达。
               a.title                                      AS userTitle,
               b.title                                      AS pageTitle,
               st.short_name                                AS siteShortName,
               st.brand_name                                AS siteBrandName,
               -- 没有 canonical 记录时（无源书签）当作首页处理：那种书签只有用户自己的标题
               COALESCE(b.url_path = '/' AND b.url_query = '' AND b.url_fragment = '', true) AS rootPage,
               COALESCE(a.description, b.description)       AS description,
               a.pinned                                     AS pinned,
               a.link_type                                  AS linkType,
               b.is_activity                                AS isActivity,
               b.url_host                                   AS urlHost,
               -- 过渡期：NSFW 判定的写入端还在 bookmark 上（上移到站点级是下一批的事），
               -- 两层任一命中都算命中 —— 朝"标记"方向失败是安全的那一侧。
               -- 判定上移之后这里收敛成 st.nsfw 单列。
               (COALESCE(st.nsfw, false) OR COALESCE(b.nsfw, false)) AS nsfw
            FROM user_share_bookmark s
                     JOIN bookmark_user_link a
                          ON a.id = s.bookmark_user_link_id
                     LEFT JOIN bookmark b
                               ON a.bookmark_id = b.id
                     LEFT JOIN site st
                               ON st.id = b.site_id
            WHERE s.share_id = #{shareId}
            ORDER BY s.sort
            """
    )
    @Description("查看某个分享包含的全部书签信息")
    fun bookmarksByShareId(shareId: String): List<BookmarkShow>
}
