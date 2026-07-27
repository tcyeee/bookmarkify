package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/** 管理员表格自定义列配置（宽度/显隐/排序），按 管理员ID + 表格标识 隔离 */
@TableName("admin_grid_config")
data class AdminGridConfigEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "管理员ID") var adminId: String,
    @field:Schema(description = "表格标识") var gridId: String,
    @field:Schema(description = "列配置(宽度/隐藏/排序) JSON") var configJson: String? = null,

    @field:Schema(description = "更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
) {

    val storeData: Map<String, Any?>?
        get() {
            if (configJson.isNullOrEmpty()) return null
            return jacksonObjectMapper().readValue(configJson!!)
        }
}
