import request from '@/utils/request'

export const reportApi = {
  dashboard: () => request.get('/report/dashboard'),
  salesSummary: (params) => request.get('/report/sales/summary', { params }),
  salesRanking: (params) => request.get('/report/sales/ranking', { params }),
  inventorySummary: () => request.get('/report/inventory/summary'),
  inventoryAging: () => request.get('/report/inventory/aging'),
  arap: (billType) => request.get('/report/arap', { params: { billType } }),
  profit: (params) => request.get('/report/profit', { params }),
  // v1.1.68 回收站
  recycleBin: (params) => request.get('/report/recycle/bin', { params }),
  recycleRestore: (type, id) => request.post('/report/recycle/restore', null, { params: { type, id } }),
  recyclePurge: (type, id) => request.post('/report/recycle/purge', null, { params: { type, id } })
}
