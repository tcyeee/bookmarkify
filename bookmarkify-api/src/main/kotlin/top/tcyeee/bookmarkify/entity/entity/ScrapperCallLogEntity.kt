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
    /**
     * scrapper 返回的机器可读错误码，成功时为空。
     *
     * 这个值早就被解析出来喂给 `classifyScrapperError` 了，只是从前没存下来，于是「这个站点
     * 到底败在哪一类」事后只能去 [errorMsg] 那段自由文本里用 LIKE 猜。按站点聚合败因需要它。
     *
     * 取值以 scrapper 契约为准（FETCH_FAILED / HEADLESS_FAILED / TIMEOUT / RECENTLY_FAILED /
     * HEADLESS_UNAVAILABLE / FORBIDDEN_TARGET / INVALID_URL / OSS_FAILED …），另有两个
     * **我方自造**的值标记「压根没拿到 scrapper 的回答」，它们不属于契约：
     * `SCRAPPER_UNREACHABLE`（连不上抓取服务）与 `CONTRACT_MISMATCH`（响应解析不了）。
     */
    val errorCode: String? = null,
    /**
     * **目标站点**的最终 HTTP 状态码，取自 scrapper 错误响应的 `fetch.httpStatus`。
     *
     * 与 [httpStatus] 是两个问题，这是加它的全部理由：那一列是 scrapper 回给**我们**的码，
     * `FETCH_FAILED` 恒为 502、`TIMEOUT` 恒为 504，跟目标站点长什么样毫无关系。而
     * 「403/406/412 被反爬拦下」（换台机器/换条路可能就好了）与「连不上、DNS 解析失败」
     * （站点是真的没了）需要完全相反的处置，分得开它们的只有这一列。
     *
     * 没连上目标时本就没有状态码，为空是事实而非遗漏；成功的调用也不写这一列（那恒为 2xx）。
     */
    val targetStatus: Int? = null,
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
