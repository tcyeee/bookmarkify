package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.monitor.LogContext
import java.time.LocalDateTime

/**
 * 一次**有后果的后台操作**的审计记录。
 *
 * 与 [ConfigChangeLogEntity] 是同一件事的两半：那张只管 `system_config` 的写入（它需要记下
 * 完整的新旧 JSON），这张管其余所有被 [top.tcyeee.bookmarkify.config.audit.AdminAudit]
 * 标注的后台动作 —— 删页面、下架分享、改 NSFW、跑清理、重算判定、后台登录。
 *
 * [operatorName] 是写入时的昵称快照，理由同 [UserBehaviorLogEntity.nickNameSnapshot]：
 * 管理员之后改名不该让历史记录跟着变，而按 id 反查当前昵称回答不了"当时叫什么"。
 */
@TableName("admin_action_log")
data class AdminActionLogEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "动作中文名，取自 @AdminAudit") val action: String,
    @field:Schema(description = "控制器方法名，排查时用来定位代码") val handler: String,
    @field:Schema(description = "HTTP 方法") val httpMethod: String? = null,
    @field:Schema(description = "请求路径（含 query）") val path: String? = null,
    /**
     * 操作对象的 id（`@PathVariable` 的第一个值，没有则为空）。
     *
     * 刻意只取路径变量而不去猜请求体里哪个字段是"目标"：后台的入参形状各不相同，猜错会让这一列
     * 看起来可信却指向错误的记录，比留空更糟。请求体整体记在 [params] 里，需要时去那儿看。
     */
    @field:Schema(description = "操作对象ID") val targetId: String? = null,
    @field:Schema(description = "请求参数摘要(JSON，超长截断)") val params: String? = null,
    @field:Schema(description = "是否成功") val success: Boolean,
    @field:Schema(description = "失败原因") val errorMsg: String? = null,
    @field:Schema(description = "操作者ID；后台登录失败时为空") val operatorId: String? = null,
    @field:Schema(description = "操作者昵称快照") val operatorName: String? = null,
    @field:Schema(description = "耗时(ms)") val durationMs: Long = 0,
    /** 见 [ScrapperCallLogEntity.requestId]：同一次请求的文本日志由此串得起来 */
    val requestId: String? = LogContext.currentRequestId(),
    @field:Schema(description = "发生时间") val createTime: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        /**
         * 参数摘要的入库长度上限。
         *
         * 后台入参里最大的是系统书签集的链接批量（几十个 URL），1000 字符足够看出"做了什么"，
         * 而完整内容本身不是审计要回答的问题 —— 要还原那次改动，该看的是被改对象的当前状态。
         */
        const val MAX_PARAMS_LEN = 1000
    }
}
