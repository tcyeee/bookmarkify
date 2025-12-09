package top.tcyeee.bookmarkify.config.result

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.exception.ErrorType

data class ResultWrapper(
    @field:Schema(name = "是否成功, true 是 false 否") var ok: Boolean = false,
    @field:Schema(name = "提示编码，也即 错误码，非 0 都是失败") var code: Int = 0,
    @field:Schema(name = "数据主体", nullable = true) var data: Any? = null,

    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(name = "错误提示，用户可阅读", nullable = true) var msg: String? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(name = "当前页码", nullable = true) var currentPage: Int? = null,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    @field:Schema(name = "全部页码", nullable = true) var total: Int? = null
) {
    companion object {
        fun ok(data: Any) = ResultWrapper(
            code = ErrorType.S0.code(),
            data = data,
            ok = true
        )

        fun ok() = ResultWrapper(
            code = ErrorType.S0.code(),
            ok = true
        )

        fun error(type: ErrorType) = ResultWrapper(
            code = type.code(),
            msg = type.msg,
            ok = false
        )

        fun error(type: ErrorType, msg: String) = ResultWrapper(
            code = type.code(),
            msg = msg,
            ok = false
        )
    }
}
