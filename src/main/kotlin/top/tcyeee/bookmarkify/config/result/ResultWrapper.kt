package top.tcyeee.bookmarkify.config.result

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.exception.ErrorType

@Schema(name = "返回结果包装类")
data class ResultWrapper<T>(
    @Schema(name = "数据主体", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    var data: T? = null,

    @Schema(name = "是否成功, true 是 false 否")
    var ok: Boolean = false,

    @Schema(name = "提示编码，也即 错误码，非 0 都是失败")
    var code: Int = 0,

    @Schema(name = "错误提示，用户可阅读", nullable = true)
    var msg: String? = null,

    @Schema(name = "当前页码", nullable = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    var currentPage: Int? = null,

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "全部页码", nullable = true)
    var total: Int? = null
) {

    companion object {
        fun <T> ok(data: T): ResultWrapper<T> {
            val result = ok<T>()
            result.data = data
            return result
        }

        fun <T> ok(): ResultWrapper<T> {
            return ResultWrapper(
                code = ErrorType.S0.code(),
                msg = ErrorType.S0.msg(),
                ok = true
            )
        }

        fun error(type: ErrorType): ResultWrapper<Any> {
            return ResultWrapper(
                code = type.code(),
                msg = type.msg(),
                ok = false
            )
        }

        fun error(type: ErrorType, msg: String): ResultWrapper<Any> {
            return ResultWrapper(
                code = type.code(),
                msg = msg,
                ok = false
            )
        }
    }
}