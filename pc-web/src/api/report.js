import request from '@/utils/request'

export const reportApi = {
  dashboard: () => request.get('/report/dashboard'),
  salesSummary: (params) => request.get('/report/sales/summary', { params }),
  salesRanking: (params) => request.get('/report/sales/ranking', { params }),
  inventorySummary: () => request.get('/report/inventory/summary'),
  inventoryAging: () => request.get('/report/inventory/aging'),
  arap: (billType) => request.get('/report/arap', { params: { billType } }),
  profit: (params) => request.get('/report/profit', { params })
}
