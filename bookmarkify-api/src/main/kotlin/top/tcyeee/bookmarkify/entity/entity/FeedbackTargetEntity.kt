package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 反馈的「所属产品」候选项。管理员可在后台自由增删。
 *
 * 这不是一个 Kotlin `enum`（因而**不进** `enums.generated.ts`）：取值在运行时由管理员维护，
 * 编译期并不知道。默认三项 Bookmarkify / Vialite / AgentTool 由迁移和 `AppInit` 补种。
 */
@TableName("feedback_target")
data class FeedbackTargetEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "产品名，全局唯一") val name: String,
    @field:Schema(description = "展示排序，小的在前") val sort: Int = 0,
    @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
)
