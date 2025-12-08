package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.BackgroundImageEntity
import top.tcyeee.bookmarkify.entity.entity.UserFile
import top.tcyeee.bookmarkify.mapper.BackgroundImageMapper
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.server.IBackgroundImageService

/**
 * 用户图片背景 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class BackgroundImageServiceImpl(
    private val fileMapper: FileMapper
) : IBackgroundImageService,
    ServiceImpl<BackgroundImageMapper, BackgroundImageEntity>() {

    override fun getFileById(id: String): UserFile =
        getById(id).let { fileMapper.selectById(it.fileId) }
}
