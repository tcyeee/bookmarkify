package top.tcyeee.bookmarkify.config.filter

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.server.IAccessTokenService
import top.tcyeee.bookmarkify.utils.ExtensionAuthUtils

/**
 * /extension 路径专用鉴权拦截器：与 Sa-Token 的 [top.tcyeee.bookmarkify.utils.StpKit] 会话体系完全独立，
 * 校验插件请求头里的 [ExtensionAuthUtils.TOKEN_HEADER]，通过后把 uid 写入 request attribute
 * 供 [ExtensionAuthUtils.currentUid] 读取。详见根目录 ACCESS_TOKEN_DESIGN.md。
 *
 * @author tcyeee
 */
@Component
class ExtensionTokenInterceptor(private val accessTokenService: IAccessTokenService) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val token = request.getHeader(ExtensionAuthUtils.TOKEN_HEADER)
        if (token.isNullOrBlank()) throw CommonException(ErrorType.E125)
        val uid = accessTokenService.verify(token) ?: throw CommonException(ErrorType.E125)
        request.setAttribute(ExtensionAuthUtils.UID_ATTRIBUTE, uid)
        return true
    }
}
