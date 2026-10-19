import request from '@/utils/request'

// v1.1.90 EM: 设备主数据 + 设备-工序 M2M
// v1.1.96+: em/maintain-{plan,order,record} 维保三件套
// 门禁: equipment:master:* / equipment:maintain:* / equipment:maintain-*:*

export const equipmentApi = {
  /** 列表分页 (workshop/type 过滤) */
  page: (params) => request.get('/base/equipment/page', { params }),
  /** 详情 */
  detail: (id) => request.get(`/base/equipment/${id}`),
  /** 保存 (id 有则改, 无则新) */
  save: (data) => request.post('/base/equipment', data),
  /** 软删 (级联清工序绑定) */
  delete: (id) => request.delete(`/base/equipment/${id}`),
  /** 全量 (device picker / 档案) */
  listAll: (params) => request.get('/base/equipment/list', { params }),
  /** 按车间筛 (App/PC 都可用) */
  listByWorkshop: (workshop) =>
    request.get('/base/equipment/list', { params: { workshop } })
}

export const equipmentProcessApi = {
  /** 按设备查绑定的工序 */
  list: (equipmentId) =>
    request.get('/base/equipment/process/list', { params: { equipmentId } }),
  /** 批量重绑 (传数组, 老绑定全部 softDelete 后重插) */
  bind: (payload) => request.post('/base/equipment/process/bind', payload)
}

// v1.1.96+ 维保计划
export const maintainPlanApi = {
  page: (params) => request.get('/em/maintain-plan/page', { params }),
  save: (data) => request.post('/em/maintain-plan', data),
  delete: (id) => request.delete(`/em/maintain-plan/${id}`)
}

// v1.1.96+ 维保工单
export const maintainOrderApi = {
  page: (params) => request.get('/em/maintain-order/page', { params }),
  detail: (id) => request.get(`/em/maintain-order/${id}`),
  save: (data) => request.post('/em/maintain-order', data),
  complete: (id, body) => request.post(`/em/maintain-order/${id}/complete`, body),
  delete: (id) => request.delete(`/em/maintain-order/${id}`)
}

// v1.1.96+ 维保记录
export const maintainRecordApi = {
  page: (params) => request.get('/em/maintain-record/page', { params }),
  detail: (id) => request.get(`/em/maintain-record/${id}`),
  check: (id, body) => request.post(`/em/maintain-record/${id}/check`, body)
}
