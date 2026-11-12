/**
 * 列表页分页 handler 的统一实现。
 *
 * 之前 22 个页面各抄一份:
 *   function queryOnPageChange(p) { if (p === query.pageNum) return; query.pageNum = p; loadData() }
 *   function queryOnSizeChange(s) { if (s === query.pageSize) return; query.pageSize = s; query.pageNum = 1; loadData() }
 *
 * el-pagination 的 handler 必须显式接参, 否则 pageNum 不更新、点第 2 页没响应 (v1.1.112 R20)。
 * 换页时回到第 1 页、每页条数不变时不重复请求, 这两条语义保持不变。
 *
 * 用法: const { queryOnPageChange, queryOnSizeChange } = useTableQuery(query, loadData)
 * loader 传函数名即可 (要求是函数声明, 否则取值时机对不上)。
 */
export function useTableQuery(query, loader) {
  function queryOnPageChange(p) {
    if (p === query.pageNum) return
    query.pageNum = p
    loader()
  }

  function queryOnSizeChange(s) {
    if (s === query.pageSize) return
    query.pageSize = s
    query.pageNum = 1
    loader()
  }

  return { queryOnPageChange, queryOnSizeChange }
}
