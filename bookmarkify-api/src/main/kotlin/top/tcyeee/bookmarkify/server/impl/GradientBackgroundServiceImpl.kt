package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.GradientBackgroundEntity
import top.tcyeee.bookmarkify.mapper.GradientBackgroundMapper
import top.tcyeee.bookmarkify.server.IGradientBackgroundService

/**
 * 用户渐变背景 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class GradientBackgroundServiceImpl : IGradientBackgroundService,
    ServiceImpl<GradientBackgroundMapper, GradientBackgroundEntity>()
