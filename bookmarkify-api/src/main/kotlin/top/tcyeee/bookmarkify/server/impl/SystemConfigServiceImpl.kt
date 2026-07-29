package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.SystemConfigEntity
import top.tcyeee.bookmarkify.mapper.SystemConfigMapper
import top.tcyeee.bookmarkify.server.ISystemConfigService
import java.time.LocalDateTime

@Service
class SystemConfigServiceImpl :
    ISystemConfigService, ServiceImpl<SystemConfigMapper, SystemConfigEntity>() {

    private fun queryEntity(key: String): SystemConfigEntity? =
        ktQuery().eq(SystemConfigEntity::configKey, key).one()

    override fun getValue(key: String): String? = queryEntity(key)?.configValue

    @Synchronized
    override fun setValue(key: String, value: String): Boolean {
        val existed = queryEntity(key)
        return if (existed != null) {
            existed.configValue = value
            existed.updateTime = LocalDateTime.now()
            updateById(existed)
        } else {
            save(SystemConfigEntity(configKey = key, configValue = value))
        }
    }
}
