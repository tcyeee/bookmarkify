package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import top.tcyeee.bookmarkify.entity.ConfigChangeLogSearchParams
import top.tcyeee.bookmarkify.entity.ConfigChangeLogVO

interface IConfigChangeLogService {
    /** 系统配置变更记录，按时间倒序分页；每行附带服务端算好的逐字段差异 */
    fun adminListAll(params: ConfigChangeLogSearchParams): IPage<ConfigChangeLogVO>
}
