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
    /**
     * 实际使用的抓取层：HTTP(Layer1 普通 HTTP) / HEADLESS(Layer2 无头浏览器) / SITE_API(站点官方 API 救援)。
     *
     * 与 [source] 不是一回事：[source] 说的是元数据从页面哪个标签里取的，这一列说的是
     * 页面本身是用什么手段弄回来的——被反爬拦下后由站点 API 救回来的页面，source 仍可能是 html。
     * 抓取失败时为空。
     */
    val layerUsed: String? = null,
    val durationMs: Long,
    val errorMsg: String? = null,
    val createTime: LocalDateTime = LocalDateTime.now(),
)
