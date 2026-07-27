package top.tcyeee.bookmarkify.utils

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType

/**
 * /extension 路径下接口的鉴权工具：uid 由 [top.tcyeee.bookmarkify.config.filter.ExtensionTokenInterceptor]
 * 校验 [TOKEN_HEADER] 后写入 request attribute，与 Sa-Token/[BaseUtils] 完全独立。
 * 详见根目录 ACCESS_TOKEN_DESIGN.md。
 *
 * @author tcyeee
 */
object ExtensionAuthUtils {
    const val TOKEN_HEADER = "X-Extension-Token"
    const val UID_ATTRIBUTE = "EXTENSION_UID"

    fun currentUid(): String {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: throw CommonException(ErrorType.E125)
        return attrs.request.getAttribute(UID_ATTRIBUTE) as? String ?: throw CommonException(ErrorType.E125)
    }
}
