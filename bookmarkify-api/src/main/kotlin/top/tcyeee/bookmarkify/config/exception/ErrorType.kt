package top.tcyeee.bookmarkify.config.exception

/**
 * 自定义异常类型 新增的错误格式必须为 E(error) + code(错误编号)命名,否则无法解析
 *
 * @author tcyeee
 * @date 2022/10/12 11:12
 */
enum class ErrorType(var msg: String) {
    S0("success"),

    /* 会在前端提示的部分 */
    E101("请先登录!"),
    E102("请求参数错误!"),
    E103("上传头像格式错误"),
    E104("文件大小超过限制"),
    E105("短信验证码过期,请点击重新获取"),
    E106("短信验证码输入错误"),
    E107("请求过于频繁，请稍后再试"),

    /* 不提示的部分 */
    E201("Websocket认证失败"),
    E202("用户UID无法查询，用户信息丢失"),
    E203("REDIS断开连接"),
    E212("阿里云短信回执查询时候SmsStatus枚举解析异常"),
    E213("阿里云短信回执查询时候SmsType枚举解析异常"),
    E214("阿里云短信发送失败"),
    E215("用户信息丢失"),
    E217("获取企业微信AccessToken失败"),

    E303("域名解析错误"),

    /* 极端错误 */
    E999("服务器繁忙");

    fun code(): Int = this.name.substring(1).toInt()
}
