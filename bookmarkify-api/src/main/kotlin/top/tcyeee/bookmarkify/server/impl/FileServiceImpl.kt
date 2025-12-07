package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.File
import top.tcyeee.bookmarkify.mapper.FileMapper
import top.tcyeee.bookmarkify.server.IFileService

/**
 * 文件记录 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:05
 */
@Service
class FileServiceImpl : IFileService, ServiceImpl<FileMapper, File>()
