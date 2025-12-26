package top.tcyeee.bookmarkify.config.exception


/**
 * 自定义异常,用于向前端发出提醒
 *
 * @author tcyeee
 * @date 2020/6/19 09:42
 */
class CommonException(
    val errorType: ErrorType, val customMessage: String? = null
) : RuntimeException(buildMessage(errorType, customMessage)) {
    companion object {
        private fun buildMessage(errorType: ErrorType, customMessage: String?): String =
            if (customMessage.isNullOrBlank()) errorType.msg
            else "${errorType.msg} :: $customMessage"
    }
}