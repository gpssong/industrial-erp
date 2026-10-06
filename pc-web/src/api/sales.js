import request from '@/utils/request'

// v1.1.75: AI 集成 — 只读解读端点 + agent (复用 report:view 权限, 超管 orRole 短路)
export const aiApi = {
  analyzeSalDelivery: (id) => request.get(`/ai/analyze/sal-delivery/${id}`),
  analyzePurReceipt: (id) => request.get(`/ai/analyze/pur-receipt/${id}`),
  analyzeSalOrder: (id) => request.get(`/ai/analyze/sal-order/${id}`),
  analyzePrdOrder: (id) => request.get(`/ai/analyze/prd-order/${id}`),
  // v1.1.75 任务2: agent 自由问答 (只读查询 + 写操作"提议", 提议需前端确认后执行)
  // v1.1.79: 单独放大超时到 180s — 后端 AgentService 最多 6 轮 LlmClient (每轮 60s, 最坏 360s,
  // 实际 1-6 轮 5-30s/轮, 总 30-180s). 全局 axios 30s 不够, 这里覆盖. 其它 AI 调用 (analyze/
  // ragSearch/replenishSuggest 短查询) 保持默认 30s. 上游 Agnes/MiniMax 卡死时 180s 超时兜底.
  agentChat: (question) => request.post('/ai/agent/chat', { question }, { timeout: 180000 }),
  // v1.1.75 任务3: RAG 文档检索 + 补货预测
  ragSearch: (keyword, limit) => request.get('/ai/rag/search', { params: { keyword, limit } }),
  replenishSuggest: (keyword, limit) => request.get('/ai/replenish/suggest', { params: { keyword, limit } })
}

export const salOrderApi = {
  page: (params) => request.get('/sales/order/page', { params }),
  detail: (id) => request.get(`/sales/order/${id}`),
  add: (data) => request.post('/sales/order', data),
  update: (data) => request.put('/sales/order', data),
  delete: (id) => request.delete(`/sales/order/${id}`),
  check: (id) => request.post(`/sales/order/${id}/check`),
  uncheck: (id) => request.post(`/sales/order/${id}/uncheck`),
  getLastPrice: (customerId, productId) => request.get('/sales/order/last-price', { params: { customerId, productId } }),
  // v1.1.41: 订单发货明细汇总 (已发/未发数量)
  getDeliverySummary: (orderId) => request.get(`/sales/order/${orderId}/delivery-summary`)
}

export const salDeliveryApi = {
  page: (params) => request.get('/sales/delivery/page', { params }),
  detail: (id) => request.get(`/sales/delivery/${id}`),
  add: (data) => request.post('/sales/delivery', data),
  update: (data) => request.put('/sales/delivery', data),
  delete: (id) => request.delete(`/sales/delivery/${id}`),
  check: (id) => request.post(`/sales/delivery/${id}/check`),
  uncheck: (id) => request.post(`/sales/delivery/${id}/uncheck`),
  getLastPrice: (customerId, productId) => request.get('/sales/delivery/last-price', { params: { customerId, productId } }),
  // v1.1.7+ 客户历史销售产品 (按出库日期 DESC, limit 50) — 销售出库新增弹窗底部参考用
  getCustomerHistoryProducts: (customerId) => request.get('/sales/delivery/customer-history-products', { params: { customerId } }),
  // v1.1.38: 按源订单 ID 查询关联出库单 (追溯入口)
  pageByOrderId: (orderId, params) => request.get('/sales/delivery/page-by-order', { params: { orderId, ...params } })
}

export const salReturnApi = {
  page: (params) => request.get('/sales/return/page', { params }),
  detail: (id) => request.get(`/sales/return/${id}`),
  add: (data) => request.post('/sales/return', data),
  check: (id) => request.post(`/sales/return/${id}/check`),
  uncheck: (id) => request.post(`/sales/return/${id}/uncheck`)
}
