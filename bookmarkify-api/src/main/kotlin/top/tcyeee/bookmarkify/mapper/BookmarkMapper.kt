package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.dto.StuckLoadingItem
import top.tcyeee.bookmarkify.entity.dto.StuckLoadingStats
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 3/10/24 22:19
 */
@Mapper
interface BookmarkMapper : BaseMapper<BookmarkEntity> {

    @Select(
        """
            SELECT a.page_id                            AS pageId,
               a.uid                                        AS uid,
               a.id                                         AS bookmarkId,
               a.url_full                                   AS urlFull,
               a.layout_node_id                             AS layoutNodeId,
               CONCAT(b.url_scheme,'://', b.url_host) AS urlBase,
               -- 三层的标题分开带上来，最终显示哪个由 BookmarkDisplayPolicy 决定。
               -- 此前这里是 COALESCE(a.title, b.title) AS title，把「用户改过」和
               -- 「没改过」压成了同一个值，优先级也就无从表达。
               a.title                                      AS userTitle,
               b.title                                      AS pageTitle,
               b.app_name                                   AS pageAppName,
               st.short_name                                AS siteShortName,
               st.brand_name                                AS siteBrandName,
               -- 没有 canonical 记录时（无源书签）当作首页处理：那种书签只有用户自己的标题
               COALESCE(b.url_path = '/' AND b.url_query = '' AND b.url_fragment = '', true) AS rootPage,
               COALESCE(a.description, b.description)       AS description,
               a.pinned                                     AS pinned,
               a.pinned_sort                                AS pinnedSort,
               a.link_type                                  AS linkType,
               b.is_activity                                AS isActivity,
               b.url_host                                   AS urlHost,
               -- NSFW 是站点级判定：同一域名不必逐页判一遍。页面级那份副本已删。
               COALESCE(st.nsfw, false)                     AS nsfw
            FROM bookmark a
                     LEFT JOIN page b
                               ON a.page_id = b.id
                     LEFT JOIN site st
                               ON st.id = b.site_id
            where a.uid = #{uid}
            """
    )
    @Description("查看用户的全部书签信息")
    fun allBookmarkByUid(uid: String): List<BookmarkShow>

    @Select(
        """
            SELECT a.page_id                            AS pageId,
               a.uid                                        AS uid,
               a.id                                         AS bookmarkId,
               a.url_full                                   AS urlFull,
               a.layout_node_id                             AS layoutNodeId,
               CONCAT(b.url_scheme,'://', b.url_host) AS urlBase,
               -- 三层的标题分开带上来，最终显示哪个由 BookmarkDisplayPolicy 决定。
               -- 此前这里是 COALESCE(a.title, b.title) AS title，把「用户改过」和
               -- 「没改过」压成了同一个值，优先级也就无从表达。
               a.title                                      AS userTitle,
               b.title                                      AS pageTitle,
               b.app_name                                   AS pageAppName,
               st.short_name                                AS siteShortName,
               st.brand_name                                AS siteBrandName,
               -- 没有 canonical 记录时（无源书签）当作首页处理：那种书签只有用户自己的标题
               COALESCE(b.url_path = '/' AND b.url_query = '' AND b.url_fragment = '', true) AS rootPage,
               COALESCE(a.description, b.description)       AS description,
               a.pinned                                     AS pinned,
               a.pinned_sort                                AS pinnedSort,
               a.link_type                                  AS linkType,
               b.is_activity                                AS isActivity,
               b.url_host                                   AS urlHost,
               -- NSFW 是站点级判定：同一域名不必逐页判一遍。页面级那份副本已删。
               COALESCE(st.nsfw, false)                     AS nsfw
            FROM bookmark a
                     LEFT JOIN page b
                               ON a.page_id = b.id
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
                   a.page_id    AS pageId,
                   a.url_full       AS urlFull,
                   a.layout_node_id AS layoutNodeId
            FROM bookmark a
                     JOIN user_layout_node n ON n.id = a.layout_node_id
            WHERE a.deleted = false
              AND n.type = 'BOOKMARK_LOADING'
              AND a.dispatch_attempts < #{maxAttempts}
              AND (a.page_id = 'LOADING' OR n.created_at < #{staleBefore})
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
        - page_id = 'LOADING'：批量导入写下的占位，导入路径**从不投递事件**（几千条逐个投递会把
          解析线程池连同队列一起打满，最终回退到调用线程——也就是 HTTP 请求线程——同步跑完整段抓取），
          所以它们从一开始就在等这里来捞，无需等待陈旧阈值。
        - 其余：addOne 投递过事件但事件丢失(进程重启/线程池饱和)，必须等过了陈旧阈值才能断定，
          否则会和还在途中的那次解析撞车。

        `dispatch_attempts < maxAttempts` 这个条件是为**队头阻塞**加的：本查询按 created_at 升序
        取前 limit 条，而补投递锁只是让在途的那些被跳过、并不改变它们仍然排在最前面这一事实。
        于是一批「永远收不了口」的记录会稳定占满这 limit 个名额，后面的行一轮都轮不到。
        超过上限的行改由 [findExhaustedLoading] 捞出来就地终结。
        """
    )
    fun findStuckLoading(
        @Param("staleBefore") staleBefore: LocalDateTime,
        @Param("limit") limit: Int,
        @Param("maxAttempts") maxAttempts: Int,
    ): List<StuckLoadingItem>

    @Select(
        """
            SELECT a.id             AS userLinkId,
                   a.uid            AS uid,
                   a.page_id    AS pageId,
                   a.url_full       AS urlFull,
                   a.layout_node_id AS layoutNodeId
            FROM bookmark a
                     JOIN user_layout_node n ON n.id = a.layout_node_id
            WHERE a.deleted = false
              AND n.type = 'BOOKMARK_LOADING'
              AND a.dispatch_attempts >= #{maxAttempts}
            ORDER BY n.created_at ASC
            LIMIT #{limit}
            """
    )
    @Description(
        """
        补投递次数已经用尽、却仍停在 BOOKMARK_LOADING 的占位。

        这些是「重试到上限也没能收口」的终局。留着它们不动有两个坏处：用户桌面上永远转圈，
        以及它们排在 [findStuckLoading] 的队头把新记录饿死。收口方式与「网址本身解析不出来」
        一致——翻成普通磁贴、不绑 canonical 书签，用户自己填的标题与网址足够渲染。
        """
    )
    fun findExhaustedLoading(
        @Param("limit") limit: Int,
        @Param("maxAttempts") maxAttempts: Int,
    ): List<StuckLoadingItem>

    @Update(
        """
            <script>
            UPDATE bookmark
            SET dispatch_attempts = dispatch_attempts + 1
            WHERE id IN
            <foreach item="id" collection="ids" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """
    )
    @Description("补投递一批占位时累加它们的重试计数，见 BookmarkEntity.dispatchAttempts")
    fun incrementDispatchAttempts(@Param("ids") ids: List<String>): Int

    @Update("UPDATE bookmark SET dispatch_attempts = 0 WHERE id = #{userLinkId}")
    @Description(
        """
        清零补投递计数。

        只在解析链路判定「我方抓取服务不可用」(E307) 而早退时调用：那一次补投递没有得到任何
        关于这个网址的结论，不该算在它头上。少了这一步，一次几十分钟的 scrapper 故障就会把
        积压里每一条记录的重试预算耗光，故障恢复后它们已经被当作「重试到上限」终结成无源书签了。
        """
    )
    fun resetDispatchAttempts(@Param("userLinkId") userLinkId: String): Int

    @Select(
        """
            SELECT count(*)                                                        AS total,
                   coalesce(max(extract(epoch FROM (now() - n.created_at))), 0)::bigint AS oldestAgeSeconds,
                   count(*) FILTER (WHERE a.page_id = 'LOADING')               AS importPending
            FROM bookmark a
                     JOIN user_layout_node n ON n.id = a.layout_node_id
            WHERE a.deleted = false
              AND n.type = 'BOOKMARK_LOADING'
            """
    )
    @Description(
        """
        「此刻有多少用户桌面在转圈、最久的转了多久」——这条链路唯一真正的 SLI。

        整套异步设计的成败就是这两个数字，而在此之前它们只能靠翻日志推断：scrapper_call_log
        记的是单次调用、bookmark_ping_log 记的是巡检，都回答不了「用户现在还在等的有几条」。
        """
    )
    fun stuckLoadingStats(): StuckLoadingStats
}