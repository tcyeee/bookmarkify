package top.tcyeee.bookmarkify.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.entity.entity.BackgroundImageEntity
import top.tcyeee.bookmarkify.server.impl.BackgroundGradientServiceImpl
import top.tcyeee.bookmarkify.server.impl.BackgroundImageServiceImpl

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
    private val imageBackgroundService: BackgroundImageServiceImpl,
    private val gradientBackgroundService: BackgroundGradientServiceImpl,
) {

    @GetMapping("/background/images")
    @Operation(summary = "获取默认背景图片列表")
    fun defaultImageBackgrounds(): ResultWrapper {
        val list = imageBackgroundService.ktQuery().eq(BackgroundImageEntity::isDefault, true).list()
        return ResultWrapper.ok(list)
    }

    @GetMapping("/background/gradients")
    @Operation(summary = "获取默认渐变背景组")
    fun defaultGradientBackgrounds(): ResultWrapper {
        val list = gradientBackgroundService.ktQuery().eq(BackgroundGradientEntity::isDefault, true).list()
        return ResultWrapper.ok(list)
    }
}

