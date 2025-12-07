package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.util.RandomUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.redis.RedisType
import top.tcyeee.bookmarkify.config.entity.sms.SmsParams
import top.tcyeee.bookmarkify.config.entity.sms.SmsResponse
import top.tcyeee.bookmarkify.config.entity.sms.SmsType
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.controller.DelayTaskScheduler
import top.tcyeee.bookmarkify.entity.entity.SmsRecord
import top.tcyeee.bookmarkify.mapper.SmsRecordMapper
import top.tcyeee.bookmarkify.server.ISmsService
import top.tcyeee.bookmarkify.utils.BaseUtils
import top.tcyeee.bookmarkify.utils.RedisUtils
import top.tcyeee.bookmarkify.utils.SmsUtils
import java.util.concurrent.TimeUnit


@Service
class SmsServiceImpl : ISmsService, ServiceImpl<SmsRecordMapper, SmsRecord>() {

    override fun sendVerificationCode(phoneNumber: String): Boolean {
        val code = RandomUtil.randomInt(1000, 9999)
        val res: SmsResponse = SmsUtils.send(SmsType.CODE, phoneNumber, SmsParams(code))
        /* 存入DATABASE */
        val entity = SmsRecord(BaseUtils.uid())
        val save = save(entity.intFirst(res))
        /* 存入REDIS */
        if (!res.sendStatus()) throw CommonException(ErrorType.E214)
        RedisUtils.set(RedisType.CODE_PHONE, code)
        /* 30秒后查询数据回执 */
        DelayTaskScheduler.delayExecute({ msgStatusCheck(entity.id) }, 30, TimeUnit.SECONDS)
        return save
    }

    override fun msgStatusCheck(id: String) {
        val entity = getById(id)
        val res = SmsUtils.queryDetil(entity)
        updateById(entity.intCheck(res))
    }
}