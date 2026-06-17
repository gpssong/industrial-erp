import request from '@/utils/request'

export const stockApi = {
  page: (params) => request.get('/inventory/stock/page', { params })
}

export const ledgerApi = {
  page: (params) => request.get('/inventory/ledger/page', { params })
}
