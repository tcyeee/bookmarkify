package top.tcyeee.bookmarkify.server.impl

import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.AdminGridConfigSaveParams
import top.tcyeee.bookmarkify.entity.AdminGridConfigVO
import top.tcyeee.bookmarkify.entity.entity.AdminGridConfigEntity
import top.tcyeee.bookmarkify.mapper.AdminGridConfigMapper
import top.tcyeee.bookmarkify.server.IAdminGridConfigService
import java.time.LocalDateTime

@Service
class AdminGridConfigServiceImpl :
    IAdminGridConfigService, ServiceImpl<AdminGridConfigMapper, AdminGridConfigEntity>() {

    private fun queryEntity(adminId: String, gridId: String): AdminGridConfigEntity? =
        ktQuery()
            .eq(AdminGridConfigEntity::adminId, adminId)
            .eq(AdminGridConfigEntity::gridId, gridId)
            .one()

    override fun queryVO(adminId: String, gridId: String): AdminGridConfigVO =
        AdminGridConfigVO(gridId = gridId, storeData = queryEntity(adminId, gridId)?.storeData)

    override fun upsert(adminId: String, gridId: String, params: AdminGridConfigSaveParams): Boolean {
        val json = JSONUtil.toJsonStr(params.storeData)
        val existed = queryEntity(adminId, gridId)
        return if (existed != null) {
            existed.configJson = json
            existed.updateTime = LocalDateTime.now()
            updateById(existed)
        } else {
            save(AdminGridConfigEntity(adminId = adminId, gridId = gridId, configJson = json))
        }
    }
}
