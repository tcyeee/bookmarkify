package top.tcyeee.bookmarkify.config.init

import cn.hutool.core.util.IdUtil
import cn.hutool.json.JSONUtil
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.event.BookmarkParseEvent
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.server.IBackgroundGradientService
import top.tcyeee.bookmarkify.server.IBookmarkService

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
    private val eventPublisher: ApplicationEventPublisher,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 见 [top.tcyeee.bookmarkify.controller.scheduled.ScheduledTasks] 上的说明 */
    @Value("\${bookmarkify.scheduling.enabled:true}")
    private var schedulingEnabled: Boolean = true

    override fun run(args: ApplicationArguments?) {
        // 启动时把调度状态喊出来。关掉时的症状是**沉默的**：drainStuckLoading 不跑，
        // 于是本地导入的书签永远停在转圈；桌面上什么都不报，日志里也什么都没有。
        // 不留这一行，下一个在本地测导入的人只会以为导入功能坏了。
        if (schedulingEnabled) {
            log.info("[AppInit] 后台定时任务：已启用")
        } else {
            log.warn(
                "[AppInit] 后台定时任务：**已关闭**(bookmarkify.scheduling.enabled=false)。" +
                    "活性巡检、解析对账、日志清理、OSS 对账均不会执行；" +
                    "批量导入的唯一消费通道 drainStuckLoading 也不跑，导入的书签会一直停在加载中。" +
                    "这是 dev profile 的默认行为（本地连的是生产库，不该并行跑定时任务）。"
            )
        }

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

        // 补齐默认书签。这里改成走 getOrCreateCanonical 而不是自己比对 key 再 batch insert：
        // canonical 记录现在必须先有 site 行、key 是四元组，那套「查出已有的 (host, path) 集合再
        // 手工 diff」的逻辑等于在这里复刻一遍去重规则，一改就会分叉。getOrCreateCanonical 本身
        // 幂等且容忍并发，重复调用只是多一次 SELECT。
        //
        // 只对 PENDING（新建或从未解析成功）的发解析事件，已有记录不重复抓。
        projectConfig.defaultBookmarkify.forEach { url ->
            runCatching { bookmarkService.getOrCreateCanonical(url) }
                .onSuccess { bookmark ->
                    if (bookmark.parseStatus == ParseStatusEnum.PENDING) {
                        eventPublisher.publishEvent(BookmarkParseEvent(bookmark.id))
                    }
                }
                // 一条默认书签的网址写错了不该让整个应用起不来
                .onFailure { log.warn("[AppInit] 默认书签初始化失败(忽略): url=$url, err=${it.message}") }
        }
    }
}