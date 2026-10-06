import request from '@/utils/request'

// v1.1.79 工作流模块: 租客主数据 + 设备维保 / 安全检查 / 应急预案 (仅 PC, 简单台账 + 审核)

// 上传端点 (系统模块, ERP_UPLOAD_PATH 已有), 走同一个 axios 实例 — 拦截器只动 config.data,
// 上传是 FormData (axios 自动识别 multipart), 不会经过 stringifyBigInts, 19 位雪花 ID 不影响.
const uploadFile = async (file) => {
  const fd = new FormData()
  fd.append('file', file)
  const r = await request({
    url: '/system/upload/file',
    method: 'POST',
    data: fd,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
  if (r.code !== 200) throw new Error(r.msg || '上传失败')
  return r.data.url
}
export { uploadFile }

export const tenantApi = {
  page: (params) => request.get('/workflow/tenant/page', { params }),
  list: () => request.get('/workflow/tenant/list'),
  detail: (id) => request.get(`/workflow/tenant/${id}`),
  add: (data) => request.post('/workflow/tenant', data),
  update: (data) => request.put('/workflow/tenant', data),
  delete: (id) => request.delete(`/workflow/tenant/${id}`)
}

export const maintainApi = {
  page: (params) => request.get('/workflow/maintain/page', { params }),
  detail: (id) => request.get(`/workflow/maintain/${id}`),
  add: (data) => request.post('/workflow/maintain', data),
  update: (data) => request.put('/workflow/maintain', data),
  delete: (id) => request.delete(`/workflow/maintain/${id}`),
  check: (id) => request.post(`/workflow/maintain/${id}/check`),
  uncheck: (id) => request.post(`/workflow/maintain/${id}/uncheck`)
}

export const safetyApi = {
  page: (params) => request.get('/workflow/safety/page', { params }),
  detail: (id) => request.get(`/workflow/safety/${id}`),
  add: (data) => request.post('/workflow/safety', data),
  update: (data) => request.put('/workflow/safety', data),
  delete: (id) => request.delete(`/workflow/safety/${id}`),
  check: (id) => request.post(`/workflow/safety/${id}/check`),
  uncheck: (id) => request.post(`/workflow/safety/${id}/uncheck`)
}

export const emergencyApi = {
  page: (params) => request.get('/workflow/emergency/page', { params }),
  detail: (id) => request.get(`/workflow/emergency/${id}`),
  add: (data) => request.post('/workflow/emergency', data),
  update: (data) => request.put('/workflow/emergency', data),
  delete: (id) => request.delete(`/workflow/emergency/${id}`),
  check: (id) => request.post(`/workflow/emergency/${id}/check`),
  uncheck: (id) => request.post(`/workflow/emergency/${id}/uncheck`)
}

// v1.1.79 hotfix: 工作流看板 — 未来 N 天内到期的 维保 / 检查 / 预案 聚合
export const dashboardApi = {
  upcoming: (days = 30) => request.get('/workflow/dashboard/upcoming', { params: { days } })
}
