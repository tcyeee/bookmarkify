package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.OssObjectSearchParams
import top.tcyeee.bookmarkify.entity.OssObjectVO
import top.tcyeee.bookmarkify.entity.OssReconcileReport
import top.tcyeee.bookmarkify.server.IOssObjectService
import top.tcyeee.bookmarkify.server.IOssReconcileService

/**
 * 文件治理后台：盘点桶里有什么、哪些没人要。
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/oss-object")
class AdminOssObjectController(
    private val ossObjectService: IOssObjectService,
    private val ossReconcileService: IOssReconcileService,
) {

    @Operation(summary = "分页查询对象账本")
    @PostMapping("/all")
    fun all(@RequestBody params: OssObjectSearchParams): IPage<OssObjectVO> = ossObjectService.adminListAll(params)

    /**
     * 手动跑一轮对账。
     *
     * 是否真的删除孤儿由 `bookmarkify.oss.reclaim-orphans` 决定，**与是谁触发的无关** ——
     * 后台点一下就顺手把对象删了不是好设计：删除是否安全取决于引用方收集逻辑是否完整，
     * 那是个配置级的判断，不该挂在一次点击上。
     */
    @Operation(summary = "立即执行一轮对账（是否回收由配置决定）")
    @PostMapping("/reconcile")
    fun reconcile(): OssReconcileReport = ossReconcileService.reconcile()
}
