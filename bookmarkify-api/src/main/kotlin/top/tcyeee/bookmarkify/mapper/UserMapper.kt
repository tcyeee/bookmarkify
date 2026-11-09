package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.entity.UserInfoEntity

/**
 * @author tcyeee
 * @date 2/13/25 13:05
 */
@Mapper
interface UserMapper : BaseMapper<UserInfoEntity> {

    @Select("SELECT count(*) FROM user_info WHERE deleted = false AND verified = true")
    @Description("统计注册用户数；verified 目前恒为 true（createVerifiedUser 是唯一建号入口，系统已无匿名/未验证用户），条件仅作防御性保留")
    fun countVerifiedUsers(): Long
}