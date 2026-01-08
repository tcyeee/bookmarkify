package top.tcyeee.bookmarkify.config.result

import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable

/**
 * @author tcyeee
 * @date 2019/11/13 15:21
 */
@Schema(description = "分页基础类")
open class PageBean : Serializable {
    @Schema(description = "当前页，默认1")
    var currentPage = 1

    @Schema(description = "每页数量，默认10")
    var pageSize = 10

    fun <T> toPage(): Page<T> = Page(currentPage.toLong(), pageSize.toLong())
}