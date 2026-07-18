import request from '@/utils/request'

export const feiePrintApi = {
  // 生产单打印预览 (返回 HTML)
  preview: (id) => request.get(`/feie/print/prd-order/${id}/preview`),
  // 生产单飞鹅打印
  print: (id) => request.post(`/feie/print/prd-order/${id}`),
  // 生产单飞鹅打印 (指定配置)
  printWithConfig: (id, configId) => request.post(`/feie/print/prd-order/${id}/config/${configId}`),

  // 打印机配置管理
  listPrinters: () => request.get('/feie/printers'),
  addPrinter: (data) => request.post('/feie/printers', data),
  updatePrinter: (data) => request.put('/feie/printers', data),
  deletePrinter: (id) => request.delete(`/feie/printers/${id}`),
  testPrinter: (ukey, deviceSn) => request.post('/feie/printers/test', null, { params: { ukey, deviceSn } })
}
