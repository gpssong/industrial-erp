import request from '@/utils/request'

export const bomApi = {
  page: (params) => request.get('/production/bom/page', { params }),
  detail: (id) => request.get(`/production/bom/${id}`),
  add: (data) => request.post('/production/bom', data),
  update: (data) => request.put('/production/bom', data),
  delete: (id) => request.delete(`/production/bom/${id}`)
}

export const prdOrderApi = {
  page: (params) => request.get('/production/order/page', { params }),
  detail: (id) => request.get(`/production/order/${id}`),
  add: (data) => request.post('/production/order', data),
  release: (id) => request.post(`/production/order/${id}/release`),
  finish: (id, params) => request.post(`/production/order/${id}/finish`, null, { params })
}
