package top.tcyeee.bookmarkify.config.entity.sms

import cn.hutool.core.bean.BeanUtil
import com.aliyun.dysmsapi20170525.models.QuerySendDetailsResponseBody
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody
import java.time.LocalDateTime

/**
 *
 * "bizId": "584407244713400335^0",
 * "code": "OK",
 * "message": "OK",
 * "requestId": "D059F777-3CE9-5880-9A24-855E3F44E43A"
 *
 *
 * "content": "【TimeDoser】您的验证码为:1111,该验证码5分钟内有效,不要告诉别人哦",
 * "errCode": "DELIVERED",
 * "phoneNum": "18657140761",
 * "receiveDate": "2025-04-15 18:36:53",
 * "sendDate": "2025-04-15 18:36:40",
 * "sendStatus": 3,
 * "templateCode": "SMS_121851388"
 *
 * @author tcyeee
 * @date 4/15/25 18:58
 */
data class SmsResponse(
    /* 请求时回执 */
    var bizId: String? = null,
    var code: String? = null,
    var message: String? = null,
    var requestId: String? = null,

    /* 查询时回执 */
    var content: String? = null,
    var errCode: String? = null,
    var phoneNum: String? = null,
    var receiveDate: LocalDateTime? = null,
    var sendDate: LocalDateTime? = null,
    var smsStatus: SmsStatus? = null,
    var smsType: SmsType? = null,
) {
    fun sendStatus(): Boolean = !code.isNullOrBlank() && code == "OK"

    fun init(res: SendSmsResponseBody, phone: String): SmsResponse {
        BeanUtil.copyProperties(res, this)
        this.phoneNum = phone
        return this
    }

    fun init(res: QuerySendDetailsResponseBody.QuerySendDetailsResponseBodySmsSendDetailDTOsSmsSendDetailDTO): SmsResponse {
        BeanUtil.copyProperties(res, this)
        smsStatus = SmsStatus.decode(res.getSendStatus())
        smsType = SmsType.decode(res.getTemplateCode())
        return this
    }
}