package top.tcyeee.bookmarkify.config.exception

import cn.dev33.satoken.exception.NotLoginException
import cn.hutool.json.JSONException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.validation.BindException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.config.result.ResultWrapper.Companion.error

@RestControllerAdvice
class GlobalExceptionHandler : ResponseBodyAdvice<Any> {
    private var log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>): Boolean {
        return returnType.method != null
    }

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse
    ): Any? {
        if (body == null) return ResultWrapper.ok<Any>()
        if (body is ResultWrapper<*>) return body

        val path = request.uri.path
        if (path.startsWith("/swagger") || path.startsWith("/v3/api-docs")) return body

        if (body is String) return body
        return ResultWrapper.ok(body)
    }


    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception, request: HttpServletRequest): ResultWrapper<*> {
        return when (e) {
            is CommonException -> error(e.errorType)

            is NullPointerException,
            is HttpMessageNotReadableException -> print(ErrorType.E102, e, request)

            is MethodArgumentNotValidException -> {
                val fieldError = e.fieldError ?: return print(ErrorType.E999, e, request)
                error(ErrorType.E102, "字段 ${fieldError.field} 校验错误: ${fieldError.defaultMessage}")
            }

            is JSONException,
            is HttpMediaTypeNotSupportedException,
            is ConstraintViolationException,
            is BindException -> print(ErrorType.E103, e, request)

            is NotLoginException -> print(ErrorType.E101, e, request)

            else -> print(ErrorType.E999, e, request)
        }
    }

    /**
     * 错误统一处理
     *
     * @param type    错误类型
     * @param e       错误详情
     * @param request 请求详情
     * @return 错误信息
     */
    private fun print(type: ErrorType, e: Exception, request: HttpServletRequest): ResultWrapper<Any> {
        if (type == ErrorType.E999) {
            log.error("Σ(oﾟдﾟoﾉ)  ${request.requestURI} | [${type.name}] ${e.message}")
            log.error("意料之外的错误: $e")
            log.error(e.cause?.toString())
        }
        return error(type)
    }
}