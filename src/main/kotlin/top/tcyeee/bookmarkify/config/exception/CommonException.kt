package top.tcyeee.bookmarkify.config.exception



/**
 * 自定义异常,用于向前端发出提醒
 *
 * @author tcyeee
 * @date 2020/6/19 09:42
 */
class CommonException(
    var errorType: ErrorType
) : RuntimeException() {
    override var message: String = errorType.msg()
}
