package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import java.time.LocalDateTime

@TableName("scrapper_call_log")
data class ScrapperCallLogEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    val url: String,
    val urlHost: String,
    val success: Boolean,
    /** HTTP 状态码；scrapper 未响应（网络异常/超时）时为空 */
    val httpStatus: Int? = null,
    /** 命中来源：og / twitter_card / json_ld / html / headless，失败时为空 */
    val source: String? = null,
    /** 是否命中 scrapper 侧缓存 */
    val cached: Boolean? = null,
    val durationMs: Long,
    val errorMsg: String? = null,
    val createTime: LocalDateTime = LocalDateTime.now(),
)
