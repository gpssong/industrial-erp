import request from '@/utils/request'

export const salOrderApi = {
  page: (params) => request.get('/sales/order/page', { params }),
  detail: (id) => request.get(`/sales/order/${id}`),
  add: (data) => request.post('/sales/order', data),
  delete: (id) => request.delete(`/sales/order/${id}`)
}

export const salDeliveryApi = {
  page: (params) => request.get('/sales/delivery/page', { params }),
  detail: (id) => request.get(`/sales/delivery/${id}`),
  add: (data) => request.post('/sales/delivery', data),
  check: (id) => request.post(`/sales/delivery/${id}/check`)
}
