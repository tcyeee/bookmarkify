package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import top.tcyeee.bookmarkify.entity.entity.PageCategory

@Mapper
interface PageCategoryMapper : BaseMapper<PageCategory> {

    /**
     * 「本地相似」：与 [pageId] 共享 ≥1 个分类、且**可展示**的其它站点首页，按共享分类数排序。
     *
     * 只回 canonical 首页（`url_path='/'`）：深链页面的标题是「某个帖子/视频」，当成"网站"
     * 推荐给别人没有意义，NSFW 判定又是站点级的（`site.nsfw`），这里一并挡掉。
     * `parse_status='SUCCESS' AND is_activity` 排除还没抓到内容 / 已失活的页面。
     *
     * 返回按相关度降序的 pageId 列表；调用方再排除「用户已收藏」并按 site 去重。
     */
    @Select(
        """
        <script>
        SELECT pc.page_id
        FROM page_category pc
                 JOIN page p ON p.id = pc.page_id
                 JOIN site s ON s.id = p.site_id
        WHERE pc.deleted = false
          AND pc.page_id != #{pageId}
          AND p.parse_status = 'SUCCESS'
          AND p.is_activity = true
          AND p.url_path = '/'
          AND p.url_query = ''
          AND p.url_fragment = ''
          AND s.nsfw = false
          AND pc.category_id IN
          <foreach item="cid" collection="categoryIds" open="(" separator="," close=")">#{cid}</foreach>
        GROUP BY pc.page_id
        ORDER BY COUNT(*) DESC, MAX(pc.create_time) DESC
        LIMIT #{limit}
        </script>
        """
    )
    fun similarPageIds(
        @Param("pageId") pageId: String,
        @Param("categoryIds") categoryIds: Collection<String>,
        @Param("limit") limit: Int,
    ): List<String>
}
