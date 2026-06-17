import request from '@/utils/request'

export const purOrderApi = {
  page: (params) => request.get('/purchase/order/page', { params }),
  detail: (id) => request.get(`/purchase/order/${id}`),
  add: (data) => request.post('/purchase/order', data),
  delete: (id) => request.delete(`/purchase/order/${id}`)
}

export const purReceiptApi = {
  page: (params) => request.get('/purchase/receipt/page', { params }),
  detail: (id) => request.get(`/purchase/receipt/${id}`),
  add: (data) => request.post('/purchase/receipt', data),
  check: (id) => request.post(`/purchase/receipt/${id}/check`)
}
