package top.tcyeee.bookmarkify.config.init

import cn.hutool.core.util.IdUtil
import cn.hutool.json.JSONUtil
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.server.IBackgroundGradientService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IKafkaMessageService
import top.tcyeee.bookmarkify.utils.WebsiteParser

/**
 * 项目初始化
 *
 * @author tcyeee
 * @date 12/7/25 22:11
 */
@Component
class AppInit(
    private val backgroundGradientService: IBackgroundGradientService,
    private val projectConfig: ProjectConfig,
    private val bookmarkService: IBookmarkService,
    private val bookmarkMapper: BookmarkMapper,
    private val messageService: IKafkaMessageService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        // 检查是否有默认渐变数据,没有则初始化
        val gradients = backgroundGradientService.ktQuery().eq(BackgroundGradientEntity::isDefault, true).list()
        if (gradients.isEmpty()) {
            val defaults = projectConfig.defaultBackgroundGradient.map {
                BackgroundGradientEntity(
                    uid = IdUtil.fastUUID(),
                    name = it.name,
                    gradient = JSONUtil.toJsonStr(it.gradient),
                    direction = it.direction,
                    isDefault = true,
                )
            }
            if (defaults.isNotEmpty()) backgroundGradientService.saveBatch(defaults)
        }

        // 检查默认书签是否被写入到数据库,找到没有被写出的那一部分,然后批量写入到数据库
        val defaultHostList = projectConfig.defaultBookmarkify.map { WebsiteParser.urlWrapper(it).urlHost }
        val hasStoreHostList = bookmarkService.findListByHost(defaultHostList).map { it.urlHost }.toSet()

        (defaultHostList - hasStoreHostList).map { WebsiteParser.urlToBookmark(it) }
            // 批量插入
            .also { bookmarkMapper.insert(it) }.map { it.id }
            // 逐一检查
            .forEach { messageService.bookmarkParse(it) }

    }
}