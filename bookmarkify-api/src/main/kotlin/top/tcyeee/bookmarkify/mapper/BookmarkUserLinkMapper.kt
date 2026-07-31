package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.dto.StuckLoadingItem
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import java.time.LocalDateTime

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
            FROM bookmark_user_link a
                     LEFT JOIN bookmark b
                               ON a.bookmark_id = b.id
                     LEFT JOIN site st
                               ON st.id = b.site_id
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
            FROM bookmark_user_link a
                     LEFT JOIN bookmark b
                               ON a.bookmark_id = b.id
                     LEFT JOIN site st
                               ON st.id = b.site_id
            where a.id = #{id}
            limit 1
            """
    )
    @Description("查看用户的单个书签")
    fun findShowById(id: String): BookmarkShow

    @Select(
        """
            SELECT a.id             AS userLinkId,
                   a.uid            AS uid,
                   a.bookmark_id    AS bookmarkId,
                   a.url_full       AS urlFull,
                   a.layout_node_id AS layoutNodeId
            FROM bookmark_user_link a
                     JOIN user_layout_node n ON n.id = a.layout_node_id
            WHERE a.deleted = false
              AND n.type = 'BOOKMARK_LOADING'
              AND (a.bookmark_id = 'LOADING' OR n.created_at < #{staleBefore})
            ORDER BY n.created_at ASC
            LIMIT #{limit}
            """
    )
    @Description(
        """
        查出「用户桌面上还在转圈」的书签，供解析任务补投递。

        以用户可见的卡死状态(BOOKMARK_LOADING)为准，而不是 canonical 书签的 parse_status：
        书签本身抓取成功、但重绑用户关联或翻转节点类型那一步失败时，parse_status 是 SUCCESS，
        任何按状态筛选的对账任务都覆盖不到，节点却会永远转下去。

        两类记录合并在一条查询里，取舍不同：
        - bookmark_id = 'LOADING'：批量导入写下的占位，导入路径**从不投递事件**（几千条逐个投递会把
          解析线程池连同队列一起打满，最终回退到调用线程——也就是 HTTP 请求线程——同步跑完整段抓取），
          所以它们从一开始就在等这里来捞，无需等待陈旧阈值。
        - 其余：addOne 投递过事件但事件丢失(进程重启/线程池饱和)，必须等过了陈旧阈值才能断定，
          否则会和还在途中的那次解析撞车。
        """
    )
    fun findStuckLoading(
        @Param("staleBefore") staleBefore: LocalDateTime,
        @Param("limit") limit: Int,
    ): List<StuckLoadingItem>
}