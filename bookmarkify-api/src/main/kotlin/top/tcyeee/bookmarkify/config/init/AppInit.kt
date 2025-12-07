package top.tcyeee.bookmarkify.config.init

import cn.hutool.core.util.IdUtil
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
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
    private val backgroundGradientService: BackgroundGradientServiceImpl
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        // 检查是否有默认渐变数据,没有则初始化
        val list = backgroundGradientService.ktQuery().eq(BackgroundGradientEntity::isDefault, true).list()
        if (list.isEmpty()) {
            val list: List<BackgroundGradientEntity> = listOf(
                BackgroundGradientEntity(
                    uid = IdUtil.fastUUID(),
                    gradient = "[\"#a69f9f\",\"#c1baba\",\"#8f9ea6\"]",
                    direction = 103,
                    isDefault = true
                ),
                BackgroundGradientEntity(
                    uid = IdUtil.fastUUID(),
                    gradient = "[\"#667eea\",\"#764ba2\"]",
                    direction = 103,
                    isDefault = true
                ),
                BackgroundGradientEntity(
                    uid = IdUtil.fastUUID(),
                    gradient = "[\"#f093fb\",\"#f5576c\"]",
                    direction = 103,
                    isDefault = true
                ),
                BackgroundGradientEntity(
                    uid = IdUtil.fastUUID(),
                    gradient = "[\"#4facfe\",\"#00f2fe\"]",
                    direction = 103,
                    isDefault = true
                ),
            )
            backgroundGradientService.saveBatch(list)
        }
    }
}