package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.BackgroundImageEntity

/**
 * 用户图片背景 Mapper
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Mapper
interface BackgroundImageMapper : BaseMapper<BackgroundImageEntity>
