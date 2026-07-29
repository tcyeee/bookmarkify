package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.SystemConfigEntity

/** 通用系统配置(key-value)存取，供各业务模块的具体配置(如书签活性检查频率)复用 */
interface ISystemConfigService : IService<SystemConfigEntity> {
    /** 按 key 查询配置原始 JSON 值，不存在返回 null */
    fun getValue(key: String): String?

    /** 按 key 新增或更新配置原始 JSON 值 */
    fun setValue(key: String, value: String): Boolean
}
