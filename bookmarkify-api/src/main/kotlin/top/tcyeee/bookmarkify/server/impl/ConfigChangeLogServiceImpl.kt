package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.ConfigChangeLogSearchParams
import top.tcyeee.bookmarkify.entity.ConfigChangeLogVO
import top.tcyeee.bookmarkify.entity.entity.ConfigChangeLogEntity
import top.tcyeee.bookmarkify.mapper.ConfigChangeLogMapper
import top.tcyeee.bookmarkify.server.IConfigChangeLogService
import top.tcyeee.bookmarkify.server.config.ConfigDiff

@Service
class ConfigChangeLogServiceImpl(
    private val objectMapper: ObjectMapper,
) : IConfigChangeLogService, ServiceImpl<ConfigChangeLogMapper, ConfigChangeLogEntity>() {

    override fun adminListAll(params: ConfigChangeLogSearchParams): IPage<ConfigChangeLogVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { toVO(it) }

    private fun toVO(entity: ConfigChangeLogEntity) = ConfigChangeLogVO(
        id = entity.id,
        configKey = entity.configKey,
        changes = ConfigDiff.of(entity.oldValue, entity.newValue, objectMapper),
        initial = entity.oldValue == null,
        oldValue = entity.oldValue,
        newValue = entity.newValue,
        operatorId = entity.operatorId,
        operatorName = entity.operatorName,
        createTime = entity.createTime,
    )
}
