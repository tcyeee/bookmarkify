package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.enums.UserBehaviorType
import java.time.LocalDateTime

/**
 * 一次用户行为的审计记录。
 *
 * 与 [AiCallLogEntity] / [ScrapperCallLogEntity] 是同一类反射，方向相反：那两张记的是我方对
 * 第三方的调用，这张记的是用户对本系统的操作。
 *
 * [nickNameSnapshot] 是写入时的昵称快照(同 [ConfigChangeLogEntity.operatorName])：用户之后
 * 改名不应该让历史记录跟着变，而按 [uid] 反查当前昵称也回答不了"当时叫什么"这个问题。
 */
@TableName("user_behavior_log")
data class UserBehaviorLogEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "所属用户ID") val uid: String,
    @field:Schema(description = "行为发生时的昵称快照") val nickNameSnapshot: String? = null,
    @field:Schema(description = "行为类型") val behaviorType: UserBehaviorType,
    @field:Schema(description = "行为详情，如URL/文件名/条目数") val detail: String? = null,
    @field:Schema(description = "发生时间") val createTime: LocalDateTime = LocalDateTime.now(),
)
