package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.bean.BeanUtil
import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.entity.sms.SmsResponse
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 4/15/25 17:04
 */
@TableName("sms_record")
data class SmsRecord(
    @TableId var id: String,
    @field:Schema(description = "关联的用户") var uid: String,

    /* 初次请求时候回执 */
    @field:Schema(description = "阿里云业务ID") var bizId: String? = null,
    @field:Schema(description = "发送状态(code)") var code: String? = null,
    @field:Schema(description = "发送状态(信息)") var message: String? = null,
    @field:Schema(description = "请求ID") var requestId: String? = null,

    /* 查询时回执 */
    @field:Schema(description = "短信内容") var content: String? = null,
    @field:Schema(description = "错误代码") var errCode: String? = null,
    @field:Schema(description = "目标手机号") var phoneNum: String? = null,
    @field:Schema(description = "创建时间") var createTime: LocalDateTime? = null,
    @field:Schema(description = "发送时间") var sendDate: LocalDateTime? = null,
    @field:Schema(description = "收到时间") var receiveDate: LocalDateTime? = null,
    @field:Schema(description = "发送状态") var smsStatusStr: String? = null,
    @field:Schema(description = "短信模板") var smsTypeStr: String? = null,
) {
    constructor(uid: String) : this(
        id = IdUtil.fastUUID(), uid = uid, createTime = LocalDateTime.now()
    )

    /* 初次请求时候回执 */
    fun intFirst(res: SmsResponse): SmsRecord {
        BeanUtil.copyProperties(res, this)
        return this
    }

    /* 二次查询时候回执 */
    fun intCheck(res: SmsResponse): SmsRecord {
        BeanUtil.copyProperties(res, this)
        smsStatusStr = res.smsStatus?.name
        smsTypeStr = res.smsType?.name
        return this
    }
}