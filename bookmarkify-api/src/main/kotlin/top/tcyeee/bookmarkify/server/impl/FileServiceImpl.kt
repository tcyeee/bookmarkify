package top.tcyeee.bookmarkify.server.impl

import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.enums.FileType
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.server.IFileService
import top.tcyeee.bookmarkify.server.IOssObjectService
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 用户文件上传。
 *
 * 原先这里维护 `user_file` 表，现在只往 `oss_object` 记一笔 —— 属主信息留在引用方
 * （`user_info.avatar_file_id` / `background_image`），因为去重之后同一份字节只有一行，
 * "这行属于谁"是个无解的问题。详见 `FILE-SYSTEM-REFACTOR.md` D4。
 *
 * @author tcyeee
 * @date 12/7/25 15:05
 */
@Service
class FileServiceImpl(
    private val ossObjectService: IOssObjectService,
) : IFileService {

    override fun updateAvatar(uid: String, file: MultipartFile): OssObjectEntity =
        upload(file, FileType.AVATAR)

    override fun uploadBackground(uid: String, file: MultipartFile): OssObjectEntity =
        upload(file, FileType.BACKGROUND)

    /**
     * 传桶 + 记账。
     *
     * **记账失败必须抛异常**，这与抓取路径的处理刻意相反。抓取那边账本只是旁路，漏记一条由
     * 对账任务从桶里反向补齐即可；而用户上传的文件，账本行就是**唯一**的记录 —— 记不上就等于
     * 对象刚传上去就成了孤儿，用户那边则表现为"上传成功但图不见了"。宁可让这次上传明确失败。
     *
     * 不记 width/height：拿真实像素得先把图解码一遍，为两列元数据在用户上传的同步路径上加一次
     * 解码不划算。对账任务本来就要遍历全桶，届时一并补齐。
     */
    private fun upload(file: MultipartFile, type: FileType): OssObjectEntity {
        val (currentName, ext) = OssUtils.uploadUserFile(file, type)
        return ossObjectService.register(
            objectKey = "${type.folder}/$currentName.$ext",
            source = OssObjectSource.USER_UPLOAD,
            // key 是随机 UUID，与字节和源地址都无关。同一个人传两次同一张图今天就是两个对象
            addressing = OssAddressing.RANDOM,
            size = file.size,
            mime = file.contentType,
            isVector = ext.equals("svg", ignoreCase = true),
        ) ?: throw CommonException(ErrorType.E224, "文件已上传但入账失败: ${type.folder}/$currentName.$ext")
    }
}
