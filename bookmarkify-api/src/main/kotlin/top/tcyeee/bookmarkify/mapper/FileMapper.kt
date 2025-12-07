package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.File

/**
 * 文件记录表 Mapper
 *
 * @author tcyeee
 * @date 12/7/25 15:05
 */
@Mapper
interface FileMapper : BaseMapper<File>
