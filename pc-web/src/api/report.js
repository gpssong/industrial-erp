import request, { API_BASE } from '@/utils/request'

export const reportApi = {
  dashboard: () => request.get('/report/dashboard'),
  salesSummary: (params) => request.get('/report/sales/summary', { params }),
  salesRanking: (params) => request.get('/report/sales/ranking', { params }),
  // v1.1.145 销售报表: 按客户/时间段查「汇总 + 明细」并下载 CSV。
  // 两个表格接口返回同一份 Table 结构 (columns/rows/totals/note), 页面按 columns 动态渲染,
  // 列名与数字格式以后端为准 —— 前端不重排、不再格式化, 免得下载的和屏幕上对不上。
  salesTable: (params) => request.get('/report/sales/by-customer', { params }),
  salesLines: (params) => request.get('/report/sales/lines', { params }),
  // CSV 只能回后端出: ① 屏幕上是当前页, 明细导出要整个查询范围的全部行; ② 权限判定不能靠前端带来的数据。
  // axios 响应拦截器只认 {code:200} 的 JSON, 二进制会被它当失败, 所以这里只给 URL, 由页面 fetch(...,credentials)。
  salesExportUrl: (params) => {
    const qs = new URLSearchParams()
    Object.keys(params || {}).forEach(k => {
      const v = params[k]
      if (v !== null && v !== undefined && v !== '') qs.append(k, v)
    })
    return `${API_BASE}/report/sales/export?${qs.toString()}`
  },
  inventorySummary: () => request.get('/report/inventory/summary'),
  inventoryAging: () => request.get('/report/inventory/aging'),
  arap: (billType) => request.get('/report/arap', { params: { billType } }),
  profit: (params) => request.get('/report/profit', { params }),
  // v1.1.68 回收站
  recycleBin: (params) => request.get('/report/recycle/bin', { params }),
  recycleRestore: (type, id) => request.post('/report/recycle/restore', null, { params: { type, id } }),
  recyclePurge: (type, id) => request.post('/report/recycle/purge', null, { params: { type, id } })
}
