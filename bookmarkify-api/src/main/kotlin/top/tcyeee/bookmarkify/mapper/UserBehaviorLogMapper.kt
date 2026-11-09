package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.entity.UserBehaviorLogEntity
import java.time.LocalDateTime

@Mapper
interface UserBehaviorLogMapper : BaseMapper<UserBehaviorLogEntity> {

    @Select(
        """
            SELECT count(DISTINCT ubl.uid)
            FROM user_behavior_log ubl
                     JOIN user_info u ON u.id = ubl.uid
            WHERE ubl.create_time >= #{since}
              AND u.deleted = false
              AND u.verified = true
            """
    )
    @Description("统计近 [since] 以来有过任意行为记录的注册用户数（去重 uid），作为「活跃用户」的口径")
    fun countActiveUsers(@Param("since") since: LocalDateTime): Long
}
