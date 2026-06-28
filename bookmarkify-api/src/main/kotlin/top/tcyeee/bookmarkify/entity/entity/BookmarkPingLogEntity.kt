package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import java.time.LocalDateTime

@TableName("bookmark_ping_log")
data class BookmarkPingLogEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    val bookmarkId: String,
    val urlHost: String,
    val alive: Boolean,
    /** ping 通后是否触发了重新解析 */
    val triggeredParse: Boolean = false,
    val createTime: LocalDateTime = LocalDateTime.now(),
)
