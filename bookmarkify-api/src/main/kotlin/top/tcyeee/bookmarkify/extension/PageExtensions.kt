import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.plugins.pagination.Page

fun <T, R> IPage<T>.mapPage(mapper: (T) -> R): IPage<R> {
    val page = Page<R>(current, size, total)
    page.records = records.map(mapper)
    return page
}