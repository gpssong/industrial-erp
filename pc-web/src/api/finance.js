import request from '@/utils/request'

export const arapApi = {
  page: (params) => request.get('/finance/arap/page', { params }),
  cash: (data) => request.post('/finance/arap/cash', data),
  // v1.1.10+: 列出客户/供应商未开票的 AR/AP 单 (开票选单界面)
  uninvoiced: (params) => request.get('/finance/arap/uninvoiced', { params })
}

// v1.1.10+: 发票管理 API
export const invoiceApi = {
  page: (params) => request.get('/finance/invoice/page', { params }),
  detail: (id) => request.get('/finance/invoice/' + id),
  issue: (data) => request.post('/finance/invoice', data),
  void: (id) => request.put('/finance/invoice/' + id + '/void'),
  byArap: (arapId) => request.get('/finance/invoice/by-arap/' + arapId),
  uninvoiced: (params) => request.get('/finance/invoice/uninvoiced', { params }),
  // v1.1.19+: 已开发票列表 (发票 + 关联源单)
  issued: (params) => request.get('/finance/invoice/issued', { params })
}