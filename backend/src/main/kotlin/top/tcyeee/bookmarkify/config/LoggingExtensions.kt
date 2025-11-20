package top.tcyeee.bookmarkify.config

/**
 * @author tcyeee
 * @date 3/12/25 14:20
 */
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// 全局扩展属性
val <T> T.log: Logger
    get() = LoggerFactory.getLogger(this!!::class.java)