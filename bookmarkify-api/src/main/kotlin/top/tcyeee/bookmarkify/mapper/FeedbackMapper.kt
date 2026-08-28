package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.FeedbackEntity

@Mapper
interface FeedbackMapper : BaseMapper<FeedbackEntity>
