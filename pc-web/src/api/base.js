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
