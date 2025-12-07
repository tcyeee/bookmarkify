package top.tcyeee.bookmarkify.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.entity.entity.GradientBackgroundEntity
import top.tcyeee.bookmarkify.entity.entity.ImageBackgroundEntity
import top.tcyeee.bookmarkify.server.impl.GradientBackgroundServiceImpl
import top.tcyeee.bookmarkify.server.impl.ImageBackgroundServiceImpl

/**
 * 默认配置相关接口
 *
 * @author tcyeee
 * @date 12/7/25 14:30
 */
@RestController
@Tag(name = "默认配置")
@RequestMapping("/default")
class DefaultContoller(
    private val imageBackgroundService: ImageBackgroundServiceImpl,
    private val gradientBackgroundService: GradientBackgroundServiceImpl,
) {

    @GetMapping("/background/images")
    @Operation(summary = "获取默认背景图片列表")
    fun defaultImageBackgrounds(): ResultWrapper {
        val list = imageBackgroundService.ktQuery().eq(ImageBackgroundEntity::isDefault, true).list()
        return ResultWrapper.ok(list)
    }

    @GetMapping("/background/gradients")
    @Operation(summary = "获取默认渐变背景组")
    fun defaultGradientBackgrounds(): ResultWrapper {
        val list = gradientBackgroundService.ktQuery().eq(GradientBackgroundEntity::isDefault, true).list()
        return ResultWrapper.ok(list)
    }
}

