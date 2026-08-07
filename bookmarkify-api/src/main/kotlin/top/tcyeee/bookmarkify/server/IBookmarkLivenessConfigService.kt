package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue

interface IBookmarkLivenessConfigService {
    /**
     * 查询全局书签活性检查频率配置，不存在或库中值不可用时返回默认值。
     *
     * 走进程内缓存（见 [top.tcyeee.bookmarkify.server.config.JsonConfigAccessor]），
     * 逐条调用是安全的，不必再为了省查询把配置一层层往下传。
     */
    fun getConfig(): BookmarkLivenessConfigValue

    /**
     * 更新全局书签巡检配置；校验不通过抛 [top.tcyeee.bookmarkify.config.exception.CommonException]。
     *
     * 整体收一个配置对象而不是逐项摊成位置参数：这里已经有六个同类型的 Int，摊开之后
     * 调用方传串了顺序既不会编译报错也不会运行报错，只是巡检节奏悄悄变成另一套。
     */
    fun updateConfig(value: BookmarkLivenessConfigValue): BookmarkLivenessConfigValue
}
