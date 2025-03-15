package top.tcyeee.bookmarkify.config.websocket

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * @author tcyeee
 * @date 3/15/25 14:49
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val webSocketHandler: WebSocketHandler
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(webSocketHandler, "/ws")
            .addInterceptors(AuthHandshakeInterceptor())
            .setAllowedOrigins("*")
    }
}