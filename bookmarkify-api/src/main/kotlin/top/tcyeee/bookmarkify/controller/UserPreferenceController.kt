package top.tcyeee.bookmarkify.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.UserPreferenceUpdateParams
import top.tcyeee.bookmarkify.entity.UserPreferenceVO
import top.tcyeee.bookmarkify.entity.entity.UserPreferenceEntity
import top.tcyeee.bookmarkify.server.IUserPreferenceService
import top.tcyeee.bookmarkify.utils.BaseUtils

@RestController
@Tag(name = "用户偏好设置")
@RequestMapping("/preference")
class UserPreferenceController(
    private val userPreferenceService: IUserPreferenceService,
) {

    @GetMapping
    @Operation(summary = "获取当前用户偏好设置")
    fun query(): UserPreferenceVO = userPreferenceService.queryShowByUid(BaseUtils.uid())

    @PostMapping
    @Operation(summary = "更新当前用户偏好设置")
    fun update(@RequestBody params: UserPreferenceUpdateParams): Boolean {
        return userPreferenceService.upsertByUid(BaseUtils.uid(), params)
    }
}

