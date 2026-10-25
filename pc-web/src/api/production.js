import request from '@/utils/request'

// v1.1.75: AI 只读解读端点 (复用 report:view 权限, 超管 orRole 短路)
export const aiApi = {
  analyzePrdOrder: (id) => request.get(`/ai/analyze/prd-order/${id}`)
}

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
  update: (data) => request.put('/production/order', data),
  release: (id) => request.post(`/production/order/${id}/release`),
  finish: (id, params) => request.post(`/production/order/${id}/finish`, null, { params }),
  void: (id) => request.post(`/production/order/${id}/void`),
  delete: (id) => request.delete(`/production/order/${id}`)
}

// v1.1.92: 领料单 / 成品入库 — 后端 controllers 已存在, 现网补齐前端入口
export const prdRequisitionApi = {
  page: (params) => request.get('/production/requisition/page', { params }),
  detail: (id) => request.get(`/production/requisition/${id}`),
  add: (data) => request.post('/production/requisition', data),
  check: (id) => request.post(`/production/requisition/${id}/check`),
  delete: (id) => request.delete(`/production/requisition/${id}`)
}

export const prdFinishedInApi = {
  page: (params) => request.get('/production/finished-in/page', { params }),
  add: (data) => request.post('/production/finished-in', data),
  check: (id) => request.post(`/production/finished-in/${id}/check`),
  delete: (id) => request.delete(`/production/finished-in/${id}`),
  uncheck: (id) => request.post(`/production/finished-in/${id}/uncheck`)
}

// v1.1.98: 半成品入库 — 打通 原料批→半成品卷批→成品批 追溯链 (XK FAI 硬门禁)
export const prdSemiInApi = {
  page: (params) => request.get('/production/semi-in/page', { params }),
  add: (data) => request.post('/production/semi-in', data),
  check: (id) => request.post(`/production/semi-in/${id}/check`),
  delete: (id) => request.delete(`/production/semi-in/${id}`),
  uncheck: (id) => request.post(`/production/semi-in/${id}/uncheck`)
}

// v1.1.80 MES 第一阶段: 工单/报工/批次 (门禁 production:report:list, 超管 orRole 短路)
// v1.1.99 C4: 生产排程 + 设备 OEE
export const mesApi = {
  /** 扫码报工: 上报某订单某道工序的合格/不良/操作人 (+ 可选工艺参数) */
  report: (payload) => request.post('/mes/report', payload),
  /** 订单工序进度 (实时看板): 该订单全部工序实例按 process_no 排序 */
  orderProgress: (orderId) => request.get(`/mes/order/${orderId}/progress`),
  /** 批次追溯 (单层, 前端逐层调) */
  batchTrace: (batchNo, depth = 0) =>
    request.get(`/mes/batch/trace/${encodeURIComponent(batchNo)}`, { params: { depth } }),
  /** 批次全链追溯 (服务端双向 BFS 展开 料→半成品→成品→客户, 一次返回整链) — v1.1.99 C2 */
  batchTraceFull: (batchNo) => request.get(`/mes/batch/trace-full/${encodeURIComponent(batchNo)}`),
  /** 批次列表 (按类型/产品筛) */
  batchList: (params) => request.get('/mes/batch/page', { params }),
  /** 工序定义 CRUD (按产品配工序链) */
  processDefList: (productId) =>
    request.get('/mes/process-def/list', { params: productId != null ? { productId } : {} }),
  processDefSave: (data) => request.post('/mes/process-def', data),
  processDefDelete: (id) => request.delete(`/mes/process-def/${id}`),
  // ===== v1.1.99 C4: 生产排程 + 设备 OEE =====
  schedulePage:   (params) => request.get('/mes/schedule/page', { params }),
  scheduleGantt:  (equipmentId) => request.get(`/mes/schedule/gantt/${equipmentId}`),
  scheduleSave:   (data) => request.post('/mes/schedule', data),
  scheduleCancel: (id) => request.post(`/mes/schedule/${id}/cancel`),
  oee:            (equipmentId) => request.get(`/mes/oee/${equipmentId}`),
  processTime:    (data) => request.post('/mes/process-time', data)
}

// ===== v1.1.119: 吹膜米克重采集 (ESP32 → /mes/weight/**) =====
// 门禁: mes:weight:list / mes:weight:edit. PC 端读 r.data.records / r.data.total (信封未拆封)
export const weightApi = {
  /** 采集器分页列表 */
  devicePage:   (params) => request.get('/mes/weight/device/page', { params }),
  /** 某采集器通道配置 */
  channelList:  (deviceId) => request.get('/mes/weight/channel/list', { params: { deviceId } }),
  /** 保存通道配置 (寄存器表+设定值+公差) */
  channelSave:  (data) => request.post('/mes/weight/channel', data),
  /** 米重曲线 (按时间正序) */
  curve:        (params) => request.get('/mes/weight/curve', { params }),
  /** 统计 (每通道均值/极值/超标率) */
  stats:        (params) => request.get('/mes/weight/stats', { params }),
  /** 采样明细分页 */
  samplePage:   (params) => request.get('/mes/weight/sample/page', { params })
}
