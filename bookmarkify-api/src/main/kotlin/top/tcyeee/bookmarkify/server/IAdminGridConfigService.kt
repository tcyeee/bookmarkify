package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.AdminGridConfigSaveParams
import top.tcyeee.bookmarkify.entity.AdminGridConfigVO
import top.tcyeee.bookmarkify.entity.entity.AdminGridConfigEntity

interface IAdminGridConfigService : IService<AdminGridConfigEntity> {
    /** 查询某个管理员在某张表格上的自定义列配置 */
    fun queryVO(adminId: String, gridId: String): AdminGridConfigVO

    /** 新增或更新某个管理员在某张表格上的自定义列配置 */
    fun upsert(adminId: String, gridId: String, params: AdminGridConfigSaveParams): Boolean
}
