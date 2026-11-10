package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import top.tcyeee.bookmarkify.config.monitor.LogContext
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
    /**
     * 产生这一行的那次 HTTP 请求的短 id（`X-Request-Id`，同时打在每行文本日志里）。
     *
     * **默认值刻意是从 MDC 现取的**，而不是让调用方传：这张表的写入点分散在好几个类里，
     * 多一个必填参数就多一处可以忘记的地方，而忘了之后没有任何症状。构造实体与写库发生在
     * 同一个线程上，`@Async` 线程池里也有（`MdcTaskDecorator` 把 MDC 透传了进去），
     * 所以就地取值是对的。定时任务等非 HTTP 入口取不到值，为 null —— 那是事实。
     *
     * 有了它，"这条失败记录对应的那几十行文本日志"第一次能被 grep 出来；反向也成立。
     * 参见 [top.tcyeee.bookmarkify.config.monitor.LogContext]。
     */
    val requestId: String? = LogContext.currentRequestId(),
    @field:Schema(description = "发生时间") val createTime: LocalDateTime = LocalDateTime.now(),
)
