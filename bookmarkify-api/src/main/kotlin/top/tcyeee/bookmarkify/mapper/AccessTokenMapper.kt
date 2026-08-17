package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.dto.AdminUserCountRow
import top.tcyeee.bookmarkify.entity.entity.AccessTokenEntity

/**
 * @author tcyeee
 */
@Mapper
interface AccessTokenMapper : BaseMapper<AccessTokenEntity> {

    @Select(
        """
            <script>
            SELECT uid, COUNT(*) AS count
            FROM access_token
            WHERE uid IN
              <foreach item="uid" collection="uids" open="(" separator="," close=")">#{uid}</foreach>
            GROUP BY uid
            </script>
            """
    )
    @Description("批量统计用户生成的访问令牌数")
    fun countByUids(@Param("uids") uids: Collection<String>): List<AdminUserCountRow>
}
