package top.tcyeee.bookmarkify.config.result

import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serial
import java.io.Serializable

/**
 * @author tcyeee
 * @date 2019/11/13 15:21
 */
@Schema(description = "分页基础类")
class PageBean : Serializable {
    @Schema(description = "当前页，默认1")
    private var currentPage = 1

    @Schema(description = "每页数量，默认10")
    private var pageSize = 10

    /**
     * 返回用于Mybatis plus的分页参数
     *
     * @return 分页参数
     */
    fun <T> page(): Page<T> {
        return Page(currentPage.toLong(), pageSize.toLong())
    }

    companion object {
        @Serial
        private var serialVersionUID = -6165567294525365157L
    }
}