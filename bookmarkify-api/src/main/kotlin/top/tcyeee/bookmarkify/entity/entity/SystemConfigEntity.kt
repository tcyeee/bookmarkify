package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/** 通用系统配置(key-value)，每种配置一行，value 为 JSON 字符串 */
@TableName("system_config")
data class SystemConfigEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "配置键，全局唯一") var configKey: String,
    @field:Schema(description = "配置值(JSON)") var configValue: String? = null,

    @field:Schema(description = "更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
)
