package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import top.tcyeee.bookmarkify.entity.enums.ShareStatus
import java.time.LocalDateTime

/**
 * 用户分享
 *
 * @author tcyeee
 */
@TableName("user_share")
data class UserShareEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Size(max = 40) @field:Schema(description = "分享人用户ID") var uid: String,
    @field:Size(max = 500) @field:Schema(description = "分享文案") var note: String? = null,
    @field:Schema(description = "过期时间(null表示永不过期)") var expireTime: LocalDateTime? = null,
    // 只会被写为 NORMAL / ADMIN_TAKEDOWN / REVIEW_REJECTED；EXPIRED 由 expireTime 实时计算，不落库(见 effectiveStatus)
    @field:Schema(description = "分享状态") var status: ShareStatus = ShareStatus.NORMAL,
    @field:Schema(description = "审核驳回理由(status=REVIEW_REJECTED 时有值)") var rejectReason: String? = null,
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
) {
    val isExpired: Boolean get() = expireTime?.isBefore(LocalDateTime.now()) == true

    val effectiveStatus: ShareStatus
        get() = when {
            status == ShareStatus.ADMIN_TAKEDOWN -> ShareStatus.ADMIN_TAKEDOWN
            status == ShareStatus.REVIEW_REJECTED -> ShareStatus.REVIEW_REJECTED
            status == ShareStatus.CANCELLED -> ShareStatus.CANCELLED
            isExpired -> ShareStatus.EXPIRED
            else -> ShareStatus.NORMAL
        }
}

/** 分享包含的书签(关联用户自己的 bookmark_user_link，而非源 bookmark，以保留用户自定义的标题/备注) */
@TableName("user_share_bookmark")
data class UserShareBookmarkEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Size(max = 64) @field:Schema(description = "所属分享ID") var shareId: String,
    @field:Size(max = 64) @field:Schema(description = "关联的用户书签ID(bookmark_user_link.id)") var bookmarkId: String,
    @field:Schema(description = "排序") var sort: Int = 0,
)
