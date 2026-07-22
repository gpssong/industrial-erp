import request from '@/utils/request'

export const stockApi = {
  page: (params) => request.get('/inventory/stock/page', { params }),
  // 列某仓库+商品下可用批次 (qty>0), v1.1.7+ 用于销售开单选 batchNo
  batches: (warehouseId, productId) => request.get('/inventory/stock/batches', { params: { warehouseId, productId } })
}

export const ledgerApi = {
  page: (params) => request.get('/inventory/ledger/page', { params })
}

/**
 * 库存盘点 API (v1.0.8+)
 * 包含: 列表/详情/新增/审核/删除 (PC 端)
 *       App 提交盘点 + 仓库账面快照 (App 外勤盘点)
 */
export const invCheckApi = {
  // PC 端
  page: (params) => request.get('/inventory/check/page', { params }),
  detail: (id) => request.get(`/inventory/check/${id}`),
  add: (data) => request.post('/inventory/check', data),
  check: (id) => request.post(`/inventory/check/${id}/check`),
  delete: (id) => request.delete(`/inventory/check/${id}`),

  // App 外勤盘点对接
  submitFromApp: (data) => request.post('/inventory/check/submit-from-app', data, { contentType: 'json' }),
  stockSnapshot: (warehouseId) => request.get(`/inventory/check/stock-snapshot/${warehouseId}`)
}
