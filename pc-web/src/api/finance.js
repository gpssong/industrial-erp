import request from '@/utils/request'

export const arapApi = {
  page: (params) => request.get('/finance/arap/page', { params }),
  cash: (data) => request.post('/finance/arap/cash', data)
}
