package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * `system_config` 每被改写一次，落一行。
 *
 * ## 为什么需要这张表
 *
 * 这里是全系统唯一能在不发版的情况下改变系统行为的入口——巡检节奏、退避曲线、归档阈值
 * 都从这里生效。而 `system_config` 只有 `update_time`：没有操作人，也没有旧值，
 * 于是"上周谁把最大重试次数调成了 2"这个问题无法回答，尽管它正是要调这些参数的理由。
 *
 * 与 `ai_call_log` / `scrapper_call_log` / `sweep_log` 是同一种反射：改变了系统行为的事情
 * 要留痕。区别只是这张表的写入频率是月级的，所以不设保留期。
 *
 * 存整份 JSON 而不是字段级 diff：配置类的字段会随版本增删，diff 是按当时的结构算出来的，
 * 半年后可能已经无法解释；原文永远可读，差异是读的时候再算的事。
 */
@TableName("config_change_log")
data class ConfigChangeLogEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "配置键(system_config.config_key)") val configKey: String,
    @field:Schema(description = "改动前的整份 JSON;null 表示该组配置的首次写入") val oldValue: String? = null,
    @field:Schema(description = "改动后的整份 JSON") val newValue: String,
    @field:Schema(description = "操作管理员ID;取不到会话时为 null") val operatorId: String? = null,
    @field:Schema(description = "操作当时的管理员昵称快照") val operatorName: String? = null,
    @field:Schema(description = "发生时间") val createTime: LocalDateTime = LocalDateTime.now(),
)
