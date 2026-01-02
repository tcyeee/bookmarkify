package top.tcyeee.bookmarkify.config.init

import cn.hutool.core.util.IdUtil
import cn.hutool.json.JSONUtil
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.server.impl.BackgroundGradientServiceImpl

/**
 * 项目初始化
 *
 * @author tcyeee
 * @date 12/7/25 22:11
 */
@Component
class AppInit(
    private val backgroundGradientService: BackgroundGradientServiceImpl,
    private val projectConfig: ProjectConfig,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        // 检查是否有默认渐变数据,没有则初始化
        val list = backgroundGradientService.ktQuery().eq(BackgroundGradientEntity::isDefault, true).list()
        if (list.isEmpty()) {
            val defaults = projectConfig.defaultBackgroundGradient.map {
                BackgroundGradientEntity(
                    uid = IdUtil.fastUUID(),
                    name = it.name,
                    gradient = JSONUtil.toJsonStr(it.gradient),
                    direction = it.direction,
                    isDefault = true,
                )
            }
            if (defaults.isNotEmpty()) {
                backgroundGradientService.saveBatch(defaults)
            }
        }
    }
}