import request from '@/utils/request'

// v1.1.90 PDM: 工艺参数 + 作业指导书 (SOP)
// 门禁: pdm:param:* / pdm:sop:*

export const paramApi = {
  /** 按工序定义 id 查参数列表 */
  list: (processDefId) => request.get('/pdm/process/param/list', { params: { processDefId } }),
  /** 保存 (id 有则改, 无则新) */
  save: (data) => request.post('/pdm/process/param', data),
  /** 软删 */
  delete: (id) => request.delete(`/pdm/process/param/${id}`)
}

export const sopApi = {
  /** 按工序定义 id 查 SOP 列表 (按 effective_date 倒序) */
  list: (processDefId) => request.get('/pdm/process/sop/list', { params: { processDefId } }),
  /** 保存 (id 有则改, 无则新) */
  save: (data) => request.post('/pdm/process/sop', data),
  /** 激活: 把同 processDef 的旧 ACTIVE 全 DEPRECATED, 当前 ACTIVE */
  activate: (id) => request.post(`/pdm/process/sop/${id}/activate`),
  /** 软删 */
  delete: (id) => request.delete(`/pdm/process/sop/${id}`)
}
