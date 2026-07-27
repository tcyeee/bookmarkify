package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.LayoutNodeFunctionEntity

interface ILayoutNodeFunctionService : IService<LayoutNodeFunctionEntity> {
    fun findByUid(uid: String):List<LayoutNodeFunctionEntity>
}

