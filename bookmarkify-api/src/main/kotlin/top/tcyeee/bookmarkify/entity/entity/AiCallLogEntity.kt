package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import top.tcyeee.bookmarkify.entity.enums.AiCallScene
import java.time.LocalDateTime

/**
 * 一次对第三方 AI（当前只有 DeepSeek）的调用记录。
 *
 * 与 [ScrapperCallLogEntity] 的区别在于这里**存了请求与响应的原文**：抓取的结果最终会落到
 * site_asset / site_page_meta，事后还能复查；AI 的输出却是即用即弃的——判成 NSFW 的理由、
 * 挑中的分类 slug 一旦写进业务表，模型当时究竟回了什么就再也无从对证。
 */
@TableName("ai_call_log")
data class AiCallLogEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    /** 服务商标识，为将来接入其它模型预留；当前恒为 DEEPSEEK */
    val provider: String = PROVIDER_DEEPSEEK,
    val scene: AiCallScene,
    /** 实际使用的模型名，优先取响应里的回显值 */
    val model: String? = null,
    /** 本次判定的对象（域名或标题），用于在日志里定位「这条是给哪个站做的」 */
    val subject: String? = null,
    val success: Boolean,
    /** HTTP 状态码；连接失败/超时时为空 */
    val httpStatus: Int? = null,
    /** 请求体原文(含完整 prompt)，超长截断 */
    val requestBody: String? = null,
    /** 响应体原文，超长截断 */
    val responseBody: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val durationMs: Long,
    val errorMsg: String? = null,
    val createTime: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        const val PROVIDER_DEEPSEEK = "DEEPSEEK"

        /**
         * 请求/响应原文的入库长度上限。
         *
         * 这两列是为了人工排查而存的，不是用来还原完整会话的：相似网站那条 prompt 最长，
         * 连同响应也就几 KB，8000 字符足够覆盖，同时挡住模型偶发的超长输出把表撑爆。
         */
        const val MAX_BODY_LEN = 8000
    }
}
