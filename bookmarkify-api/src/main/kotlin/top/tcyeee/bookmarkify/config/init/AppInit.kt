package top.tcyeee.bookmarkify.config.init

import cn.hutool.core.util.IdUtil
import cn.hutool.json.JSONUtil
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.event.BookmarkParseEvent
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.server.IBackgroundGradientService
import top.tcyeee.bookmarkify.server.IBookmarkService
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
    private val eventPublisher: ApplicationEventPublisher,
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
        // 精确匹配 (host, path)，而不是只按 host：避免「该域名下已有别的路径被收录」时，
        // 误判默认书签（域名根路径）已存在而漏建。
        val defaultUrls = projectConfig.defaultBookmarkify
        val hasStoreKeys = bookmarkService.findListByUrl(defaultUrls).map { it.urlHost to it.urlPath }.toSet()

        defaultUrls.filter { url ->
            val w = WebsiteParser.urlWrapper(url)
            (w.urlHost to (w.urlPath ?: "/")) !in hasStoreKeys
        }.map { WebsiteParser.urlToBookmark(it) }
            // 批量插入
            .also { bookmarkMapper.insert(it) }.map { it.id }
            // 逐一发布异步解析事件
            .forEach { eventPublisher.publishEvent(BookmarkParseEvent(it)) }

    }
}