package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.SmsRecord

/**
 * @author tcyeee
 * @date 4/15/25 17:18
 */
@Mapper
interface SmsRecordMapper : BaseMapper<SmsRecord>