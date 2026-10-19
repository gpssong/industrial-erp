// v1.1.101 XK — App 端 QMS API 封装 (IQC 进货查验)
// 全部走 request() 共享基地址 + cookie 鉴权
import { request } from './index.js'

/**
 * ① 列出「待检验」采购计划 (plan_status=ARRIVED) — App 端 pages/scan/in 无 planId 进入时先选计划.
 * 后端: GET /purchase/inbound-plan/arrived  (perm purchase:order:list orRole admin)
 */
export function planArrived() {
  return request({ url: '/purchase/inbound-plan/arrived', method: 'GET' })
}

/**
 * ② 加载某 IQC 详情 (head + items + attachments + plan) — App 端 pages/scan/in 主入口.
 * 后端: GET /qms/inspection/{id}/detail-for-plan
 */
export function iqcDetail(iqcId) {
  return request({ url: `/qms/inspection/${iqcId}/detail-for-plan`, method: 'GET' })
}

/**
 * ② 列出某 IQC 的全部检验项 (按 line_no).
 */
export function iqcItems(iqcId) {
  return request({ url: `/qms/iqc/${iqcId}/items`, method: 'GET' })
}

/**
 * ③ 全量替换某 IQC 的检验项 (App 端保存草稿/提交前调一次).
 * @param {Array} items  QmsIqcItem[] (id 可空, iqcId 由后端从 path 取)
 */
export function iqcSaveItems(iqcId, items) {
  return request({ url: `/qms/iqc/${iqcId}/items`, method: 'PUT', data: items })
}

/**
 * ④ 从采购计划触发, 自动建/复用 IQC 草稿.
 * 后端: POST /qms/inspection/create-from-plan
 * @returns {Promise<{iqcId: Long, ...}>}
 */
export function iqcCreateFromPlan(planId) {
  return request({ url: '/qms/inspection/create-from-plan', method: 'POST', data: { planId } })
}

/**
 * ④' 从采购订单触发, 自动建/复用 IQC 草稿 (订单为源, 完全对齐 PC).
 * 后端: POST /qms/inspection/create-from-order
 */
export function iqcCreateFromOrder(orderId) {
  return request({ url: '/qms/inspection/create-from-order', method: 'POST', data: { orderId } })
}

/**
 * ②' 加载某 IQC 详情 (订单版: head + items + attachments + order).
 * 后端: GET /qms/inspection/{id}/detail-for-order
 */
export function iqcDetailForOrder(iqcId) {
  return request({ url: `/qms/inspection/${iqcId}/detail-for-order`, method: 'GET' })
}

/**
 * ⑤' 签署合格 IQC + 放行 (订单版).
 * 后端: POST /qms/inspection/sign-for-order
 * @param {object} body  { orderId, iqcId, reportNo, sampleQty, inspectorName, photoUrls }
 */
export function iqcSignForOrder(orderId, iqcId, body) {
  return request({ url: `/qms/inspection/sign-for-order`, method: 'POST', data: { orderId, iqcId, ...body } })
}

/**
 * ⑤ App 端 "生成报告并自动入库" — 签署合格 + 自动建 DRAFT 采购入库.
 * 后端: POST /qms/inspection/{id}/sign-for-plan
 * @param {object} body  { planId, iqcId, reportNo, sampleQty, inspectorName, photoUrls }
 */
export function iqcSignForPlan(planId, iqcId, body) {
  return request({ url: `/qms/inspection/sign-for-plan`, method: 'POST', data: { planId, iqcId, ...body } })
}

/**
 * ⑥ 列出某 IQC 的附件/照片.
 */
export function iqcAttachments(iqcId) {
  return request({ url: `/qms/iqc/${iqcId}/attachments`, method: 'GET' })
}

/**
 * ⑦ 关联已上传的 url 到 IQC (uploadFile 后调).
 * @param {object} body  { fileUrl, fileName, fileSizeKb, attachType: REPORT/CONTRACT/INVOICE/LABEL_PHOTO/PACK_PHOTO/SAMPLE_PHOTO/OTHER }
 */
export function iqcBindAttachment(iqcId, body) {
  return request({ url: `/qms/iqc/${iqcId}/attachment/bind`, method: 'POST', data: body })
}

/**
 * ⑧ 标记附件已核验 (XK 留痕).
 */
export function iqcVerifyAttachment(attachmentId) {
  return request({ url: `/qms/iqc/attachment/${attachmentId}/verify`, method: 'POST' })
}

/**
 * ⑨ 从 IQC 自动建 DRAFT 采购入库单 (供 App 端"生成报告并自动入库" 调).
 * 后端: POST /purchase/receipt/create-from-iqc  { planId, iqcId }
 * @returns {Promise<{receiptId: Long}>}
 */
export function purchaseCreateFromIqc(planId, iqcId) {
  return request({ url: '/purchase/receipt/create-from-iqc', method: 'POST', data: { planId, iqcId } })
}

/**
 * ⑨' 从采购订单 + 合格 IQC 自动建 DRAFT 采购入库单 (订单版, 完全对齐 PC).
 * 后端: POST /purchase/receipt/create-from-order  { orderId, iqcId }
 * @returns {Promise<{receiptId: Long}>}
 */
export function purchaseCreateFromOrder(orderId, iqcId) {
  return request({ url: '/purchase/receipt/create-from-order', method: 'POST', data: { orderId, iqcId } })
}

/**
 * ⑩ App XK 吹膜生产全流程 — 现场签合格 FAI (半成品入库门禁).
 * 后端: POST /qms/inspection/sign-fai-for-semi-in
 * @param {object} body  { semiInId, faiId?, reportNo, sampleQty, inspectorName, photoUrls }
 */
export function signFaiForSemiIn(body) {
  return request({ url: '/qms/inspection/sign-fai-for-semi-in', method: 'POST', data: body })
}

/**
 * ⑪ App XK 吹膜生产全流程 — 现场签合格 OQC (成品入库 OQC 软门禁).
 * 后端: POST /qms/inspection/sign-oqc-for-finished-in
 * @param {object} body  { finishedInId, oqcId?, reportNo, sampleQty, inspectorName, photoUrls }
 */
export function signOqcForFinishedIn(body) {
  return request({ url: '/qms/inspection/sign-oqc-for-finished-in', method: 'POST', data: body })
}
