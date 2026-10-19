/**
 * QA 校准设备 + 校准记录 (v1.1.97)
 * 对应 Safety.vue Tab 5「检验设备校准」
 * 端点 (backend modules/qa QaCalibrationEquipmentController + QaCalibrationRecordController):
 *   GET  /qa/calibration-equipment/page         分页
 *   GET  /qa/calibration-equipment/list         全量 (右侧档案切换)
 *   GET  /qa/calibration-equipment/{id}         详情
 *   POST /qa/calibration-equipment              保存 (add/edit 共端点)
 *   DEL  /qa/calibration-equipment/{id}         删除
 *   GET  /qa/calibration-record/page            分页
 *   GET  /qa/calibration-record/{id}            详情
 *   POST /qa/calibration-record                 保存
 *   POST /qa/calibration-record/{id}/check      审核 (QA 主管, 联动推 equipment)
 *   DEL  /qa/calibration-record/{id}            删除
 */
import request from '@/utils/request'

export const calibrationEquipmentApi = {
  page:   (params) => request.get('/qa/calibration-equipment/page', { params }),
  listAll: ()      => request.get('/qa/calibration-equipment/list'),
  detail: (id)     => request.get(`/qa/calibration-equipment/${id}`),
  save:   (data)   => request.post('/qa/calibration-equipment', data),
  delete: (id)     => request.delete(`/qa/calibration-equipment/${id}`)
}

export const calibrationRecordApi = {
  page:   (params) => request.get('/qa/calibration-record/page', { params }),
  detail: (id)     => request.get(`/qa/calibration-record/${id}`),
  save:   (data)   => request.post('/qa/calibration-record', data),
  check:  (id, body) => request.post(`/qa/calibration-record/${id}/check`, body),
  delete: (id)     => request.delete(`/qa/calibration-record/${id}`)
}
