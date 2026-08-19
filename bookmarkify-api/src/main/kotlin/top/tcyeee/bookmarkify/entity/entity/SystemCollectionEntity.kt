package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import top.tcyeee.bookmarkify.entity.enums.SystemCollectionStatus
import java.time.LocalDateTime

/**
 * 管理员策展的系统书签集：一组 [PageEntity]，配一个标题/描述，作为整体展示给用户。
 *
 * 与 `user_share`（用户自己分享的一组书签）刻意分开——那是用户产出、走 AI 审核；这是管理员
 * 通过「AI 批量导出为集合」流程产出，不需要审核，`createdBy` 记的是管理员 uid 而非分享者。
 * 无 `site_`/`page_`/`user_` 前缀：不随用户或域名切换，是系统级实体，与 `system_config` 同类。
 */
@TableName("system_collection")
data class SystemCollectionEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    var title: String = "",
    var description: String = "",
    var status: SystemCollectionStatus = SystemCollectionStatus.PUBLISHED,
    /** 发布该集合的管理员 uid */
    var createdBy: String = "",
    var createTime: LocalDateTime = LocalDateTime.now(),
    var updateTime: LocalDateTime = LocalDateTime.now(),
)
