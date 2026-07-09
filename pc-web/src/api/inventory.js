import request from '@/utils/request'

export const stockApi = {
  page: (params) => request.get('/inventory/stock/page', { params }),
  // 列某仓库+商品下可用批次 (qty>0), v1.1.7+ 用于销售开单选 batchNo
  batches: (warehouseId, productId) => request.get('/inventory/stock/batches', { params: { warehouseId, productId } })
}

export const ledgerApi = {
  page: (params) => request.get('/inventory/ledger/page', { params })
}
