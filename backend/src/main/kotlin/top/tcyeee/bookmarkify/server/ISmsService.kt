package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.SmsRecord

/**
 * 短信相关
 *
 * @author tcyeee
 * @date 4/15/25 16:59
 */
interface ISmsService : IService<SmsRecord> {
    /* 发送验证码,同时存入REDIS */
    fun sendVerificationCode(phoneNumber: String): Boolean
    fun msgStatusCheck(id:String)
}