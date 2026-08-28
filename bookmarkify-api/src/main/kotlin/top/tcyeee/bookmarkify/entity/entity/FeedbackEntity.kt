package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 一条来自公开接口 `POST /feedback/submit` 的反馈。
 *
 * 调用方是外部 Agent / 集成脚本，接口**不鉴权**：只带「所属产品 + 可选邮箱 + 正文」。
 * 后台在「第三方管理 › 网站反馈组件」里读取、标记已读、批量删除。
 *
 * [target] 不做外键：目标（[FeedbackTargetEntity]）被管理员删掉后，历史反馈仍要能原样读出。
 */
@TableName("feedback")
data class FeedbackEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "所属产品，取值来自 feedback_target") val target: String,
    @field:Schema(description = "提交者联系邮箱，选填") val email: String? = null,
    @field:Schema(description = "反馈正文") val content: String,
    @field:Schema(description = "是否已读，默认未读")
    @TableField("is_read") val read: Boolean = false,
    @field:Schema(description = "提交来源 IP（排障用，可伪造）") val sourceIp: String? = null,
    @field:Schema(description = "提交方 User-Agent") val userAgent: String? = null,
    @field:Schema(description = "提交时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "首次被标记已读的时间") val readTime: LocalDateTime? = null,
) {
    companion object {
        /** 正文入库长度上限，与 `deploy/schema.sql` 的 varchar(5000) 对齐 */
        const val MAX_CONTENT_LEN = 5000

        /** 邮箱字段的入库长度上限 */
        const val MAX_EMAIL_LEN = 200
    }
}
