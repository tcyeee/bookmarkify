package top.tcyeee.bookmarkify.utils

import cn.hutool.core.date.DatePattern
import cn.hutool.core.date.DateUtil
import cn.hutool.json.JSONUtil
import com.aliyun.dysmsapi20170525.Client
import com.aliyun.dysmsapi20170525.models.QuerySendDetailsRequest
import com.aliyun.dysmsapi20170525.models.QuerySendDetailsResponseBody
import com.aliyun.dysmsapi20170525.models.SendSmsRequest
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody
import com.aliyun.teaopenapi.models.Config
import top.tcyeee.bookmarkify.config.entity.sms.SmsParams
import top.tcyeee.bookmarkify.config.entity.sms.SmsResponse
import top.tcyeee.bookmarkify.config.entity.sms.SmsType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.SmsRecord

/**
 * @author tcyeee
 * @date 4/15/25 17:43
 */
object SmsUtils {
    // 在对象初始化时就创建客户端
    private var client: Client = createClient()
    private var signName = "TimeDoser"

    // 创建客户端方法，从环境变量中读取密钥
    private fun createClient(): Client = Client(
        Config()
            .setAccessKeyId(System.getenv("OSS_ACCESS_KEY_ID"))
            .setAccessKeySecret(System.getenv("OSS_ACCESS_KEY_SECRET"))
            .setEndpoint("dysmsapi.aliyuncs.com")
    )

    /**
     * 发送短信
     *
     * @param type 短信类型(包含模板信息)
     * @param params 其中模板中使用到的参数必须填写
     */
    fun send(type: SmsType, phone: String, params: SmsParams): SmsResponse =
        send(phone, type.module, params.json())

    private fun send(phone: String, templateCode: String, templateParamsJson: String): SmsResponse {
        val sendSmsRequest = SendSmsRequest()
            .setPhoneNumbers(phone)
            .setSignName(signName)
            .setTemplateCode(templateCode)
            .setTemplateParam(templateParamsJson)
        val result: SendSmsResponseBody = client.sendSms(sendSmsRequest).body
        log.info(JSONUtil.toJsonStr(result))
        return SmsResponse().init(result, phone)
    }

    fun queryDetil(entity: SmsRecord): SmsResponse {
        val params = QuerySendDetailsRequest()
            .setPhoneNumber(entity.phoneNum)
            .setBizId(entity.bizId)
            .setCurrentPage(1)
            .setPageSize(10)
            .setSendDate(DateUtil.format(entity.createTime, DatePattern.PURE_DATE_PATTERN))
        val result: QuerySendDetailsResponseBody.QuerySendDetailsResponseBodySmsSendDetailDTOsSmsSendDetailDTO =
            client.querySendDetails(params).body.smsSendDetailDTOs.smsSendDetailDTO[0]
        log.info(JSONUtil.toJsonStr(result))
        return SmsResponse().init(result)
    }

}