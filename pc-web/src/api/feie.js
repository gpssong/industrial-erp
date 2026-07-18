import request from '@/utils/request'

export const feiePrintApi = {
  // 通用打印预览 (返回飞鹅标签格式文本)
  preview: (bizType, id) => request.get(`/feie/print/${bizType}/${id}/preview`),
  // 通用飞鹅打印
  print: (bizType, id) => request.post(`/feie/print/${bizType}/${id}`),
  // 通用飞鹅打印 (指定配置)
  printWithConfig: (bizType, id, configId) => request.post(`/feie/print/${bizType}/${id}/config/${configId}`),

  // 打印机配置管理
  listPrinters: () => request.get('/feie/printers'),
  addPrinter: (data) => request.post('/feie/printers', data),
  updatePrinter: (data) => request.put('/feie/printers', data),
  deletePrinter: (id) => request.delete(`/feie/printers/${id}`),
  testPrinter: (ukey, deviceSn) => request.post('/feie/printers/test', null, { params: { ukey, deviceSn } }),

  // 打印日志分页查询
  logPage: (params) => request.get('/feie/log/page', { params }),

  // 飞鹅打印模板 CRUD
  templatePage: (params) => request.get('/feie/templates/page', { params }),
  getTemplate: (id) => request.get(`/feie/templates/${id}`),
  addTemplate: (data) => request.post('/feie/templates', data),
  updateTemplate: (id, data) => request.put(`/feie/templates/${id}`, data),
  deleteTemplate: (id) => request.delete(`/feie/templates/${id}`),
  previewTemplate: (id) => request.post(`/feie/templates/${id}/preview`)
}