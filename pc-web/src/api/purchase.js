import request from '@/utils/request'

// v1.1.75: AI 只读解读端点 (复用 report:view 权限, 超管 orRole 短路)
export const aiApi = {
  analyzePurReceipt: (id) => request.get(`/ai/analyze/pur-receipt/${id}`),
  // v1.1.158: 视觉 OCR — 原料标签批号识别
  ocrStatus: () => request.get('/ai/ocr/status'),
  // v1.1.163: 可选 productName — 一次到货多种原料时按本行品名定向识别, 避免每行填成同一个批号
  ocrLabelBatchNo: (photoUrls, productName) => {
    const body = { photoUrls }
    if (productName && String(productName).trim()) body.productName = String(productName).trim()
    return request.post('/ai/ocr/label-batchno', body)
  }
}

export const purOrderApi = {
  page: (params) => request.get('/purchase/order/page', { params }),
  detail: (id) => request.get(`/purchase/order/${id}`),
  add: (data) => request.post('/purchase/order', data),
  update: (data) => request.put('/purchase/order', data),
  delete: (id) => request.delete(`/purchase/order/${id}`),
  check: (id) => request.post(`/purchase/order/${id}/check`),
  uncheck: (id) => request.post(`/purchase/order/${id}/uncheck`),
  getLastPrice: (supplierId, productId) => request.get('/purchase/order/last-price', { params: { supplierId, productId } })
}

export const purReceiptApi = {
  page: (params) => request.get('/purchase/receipt/page', { params }),
  detail: (id) => request.get(`/purchase/receipt/${id}`),
  add: (data) => request.post('/purchase/receipt', data),
  update: (data) => request.put('/purchase/receipt', data),
  delete: (id) => request.delete(`/purchase/receipt/${id}`),
  check: (id) => request.post(`/purchase/receipt/${id}/check`),
  uncheck: (id) => request.post(`/purchase/receipt/${id}/uncheck`),
  getLastPrice: (supplierId, productId) => request.get('/purchase/receipt/last-price', { params: { supplierId, productId } }),
  // v1.1.7+ 供应商历史采购产品 (按入库日期 DESC, limit 50) — 采购入库新增弹窗底部参考用
  getSupplierHistoryProducts: (supplierId) => request.get('/purchase/receipt/supplier-history-products', { params: { supplierId } })
}

export const purReturnApi = {
  page: (params) => request.get('/purchase/return/page', { params }),
  detail: (id) => request.get(`/purchase/return/${id}`),
  add: (data) => request.post('/purchase/return', data),
  check: (id) => request.post(`/purchase/return/${id}/check`),
  uncheck: (id) => request.post(`/purchase/return/${id}/uncheck`)
}
