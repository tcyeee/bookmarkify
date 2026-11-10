package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.AiCallLogSearchParams
import top.tcyeee.bookmarkify.entity.AiCallLogVO
import top.tcyeee.bookmarkify.entity.entity.AiCallLogEntity
import top.tcyeee.bookmarkify.mapper.AiCallLogMapper
import top.tcyeee.bookmarkify.server.IAiCallLogService
import top.tcyeee.bookmarkify.server.log.LogRetention
import java.time.LocalDateTime

/**
 * 第三方 AI 调用日志 Service 实现
 */
@Service
class AiCallLogServiceImpl :
    IAiCallLogService, ServiceImpl<AiCallLogMapper, AiCallLogEntity>() {

    override fun adminListAll(params: AiCallLogSearchParams): IPage<AiCallLogVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { AiCallLogVO(it) }

    override fun purgeExpired(): Int {
        val now = LocalDateTime.now()

        // ① 先抹正文。条件里带"至少有一列还不为空"，否则这条 UPDATE 每天都会把同一批老行
        // 再写一遍（行数照报、WAL 照产生），而它们早就已经是空的了
        val bodyBefore = now.minusDays(LogRetention.AI_BODY_DAYS)
        val scrubbed = baseMapper.update(
            null,
            KtUpdateWrapper(AiCallLogEntity::class.java)
                .set(AiCallLogEntity::requestBody, null)
                .set(AiCallLogEntity::responseBody, null)
                .lt(AiCallLogEntity::createTime, bodyBefore)
                .and {
                    it.isNotNull(AiCallLogEntity::requestBody)
                        .or().isNotNull(AiCallLogEntity::responseBody)
                }
        )

        // ② 再删整行。顺序无关紧要（被删的行本来就包含在①里），但先抹后删少写一点 WAL
        val rowBefore = now.minusDays(LogRetention.AI_ROW_DAYS)
        val deleted = baseMapper.delete(
            KtQueryWrapper(AiCallLogEntity::class.java).lt(AiCallLogEntity::createTime, rowBefore)
        )
        return scrubbed + deleted
    }
}
