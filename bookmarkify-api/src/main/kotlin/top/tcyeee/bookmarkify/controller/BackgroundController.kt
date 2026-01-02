package top.tcyeee.bookmarkify.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.entity.BackSettingParams
import top.tcyeee.bookmarkify.entity.DefaultBackgroundsResponse
import top.tcyeee.bookmarkify.entity.GradientConfigParams
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.server.impl.BackgroundConfigServiceImpl
import top.tcyeee.bookmarkify.server.impl.BackgroundGradientServiceImpl
import top.tcyeee.bookmarkify.server.impl.BackgroundImageServiceImpl
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * @author tcyeee
 * @date 12/7/25 20:48
 */
@RestController
@Tag(name = "设置相关")
@RequestMapping("/background")
class BackgroundController(
    private val userService: IUserService,
    private val imageBackgroundService: BackgroundImageServiceImpl,
    private val gradientBackgroundService: BackgroundGradientServiceImpl,
    private val backgroundConfigService: BackgroundConfigServiceImpl
) {

    @GetMapping("/default")
    @Operation(summary = "获取默认背景资源（渐变 + 图片）")
    fun defaultBackgrounds(): DefaultBackgroundsResponse = DefaultBackgroundsResponse(
        gradients = gradientBackgroundService.defaultGradientBackgrounds(),
        images = imageBackgroundService.defaultImageBackgrounds()
    )

    // TODO 获取我自定义的背景资源（渐变 + 图片）

    @PostMapping("uploadBacPic")
    @Operation(summary = "上传自定义背景图片", parameters = [Parameter(name = "file", description = "背景图片文件")])
    fun uploadBackground(@RequestParam("file") file: MultipartFile): ResultWrapper {
        val fileUrl = userService.addBacImg(file, BaseUtils.uid())
        return ResultWrapper.ok(fileUrl)
    }

    @PostMapping("updateBacColor")
    @Operation(summary = "上传渐变色背景图片")
    fun updateBacColor(@RequestBody params: GradientConfigParams): Boolean =
        userService.addBacColor(params, BaseUtils.uid())

    @PostMapping("selectBackground")
    @Operation(summary = "在已有的渐变色背景和图片背景中选择主页背景")
    fun bacSetting(@RequestBody params: BackSettingParams): Boolean = userService.bacSetting(params, BaseUtils.uid())

    @GetMapping("background/reset")
    @Operation(summary = "重制背景图片")
    fun bacReset(): Boolean = backgroundConfigService.deleteByUid(BaseUtils.uid())

}