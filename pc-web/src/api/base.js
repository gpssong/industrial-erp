import request from '@/utils/request'

export const productApi = {
  page: (params) => request.get('/base/product/page', { params }),
  detail: (id) => request.get(`/base/product/${id}`),
  add: (data) => request.post('/base/product', data),
  update: (data) => request.put('/base/product', data),
  delete: (id) => request.delete(`/base/product/${id}`),
  convert: (productId, unitId, qty) => request.get('/base/product/convert', { params: { productId, unitId, qty } })
}

export const customerApi = {
  page: (params) => request.get('/base/customer/page', { params }),
  list: () => request.get('/base/customer/list'),
  add: (data) => request.post('/base/customer', data),
  update: (data) => request.put('/base/customer', data),
  delete: (id) => request.delete(`/base/customer/${id}`)
}

export const supplierApi = {
  page: (params) => request.get('/base/supplier/page', { params }),
  list: () => request.get('/base/supplier/list'),
  add: (data) => request.post('/base/supplier', data),
  update: (data) => request.put('/base/supplier', data),
  delete: (id) => request.delete(`/base/supplier/${id}`)
}

export const warehouseApi = {
  list: () => request.get('/base/warehouse/list'),
  add: (data) => request.post('/base/warehouse', data),
  update: (data) => request.put('/base/warehouse', data),
  delete: (id) => request.delete(`/base/warehouse/${id}`)
}

export const unitApi = {
  list: () => request.get('/base/unit/list'),
  add: (data) => request.post('/base/unit', data),
  update: (data) => request.put('/base/unit', data),
  delete: (id) => request.delete(`/base/unit/${id}`)
}

/**
 * v1.1.167 标签层 (docs/32 §6.1)。
 * 渲染/打印挂 base:label:print，模板增删改挂 base:label:template。
 * 注意：每个调用方各自 try/catch，一个失败不要清空其他已取到的数据（v1.1.145 教训）。
 */
export const labelApi = {
  master: () => request.get('/base/label/master'),
  fields: (bizType) => request.get('/base/label/fields', { params: { bizType } }),
  bills: (params) => request.get('/base/label/bills', { params }),
  lines: (params) => request.get('/base/label/lines', { params }),
  preview: (data) => request.post('/base/label/preview', data),
  feiePreview: (data) => request.post('/base/label/feie/preview', data),
  feiePrint: (data) => request.post('/base/label/feie/print', data),
  logs: (params) => request.get('/base/label/logs', { params }),
  tspl: (data) => request.post('/base/label/tspl', data),
  resolve: data => request.post('/base/label/resolve', data)
}

export const labelTemplateApi = {
  list: (params) => request.get('/base/label/template/list', { params }),
  detail: (id) => request.get(`/base/label/template/${id}`),
  defaultOne: (bizType) => request.get('/base/label/template/default', { params: { bizType } }),
  add: (data) => request.post('/base/label/template', data),
  update: (data) => request.put('/base/label/template', data),
  delete: (id) => request.delete(`/base/label/template/${id}`),
  setDefault: (id) => request.put(`/base/label/template/${id}/default`),
  history: id => request.get(`/base/label/template/${id}/history`),
  historyOne: (id, seq) => request.get(`/base/label/template/${id}/history/${seq}`),
  rollback: (id, seq) => request.post(`/base/label/template/${id}/rollback`, { seq })
}
