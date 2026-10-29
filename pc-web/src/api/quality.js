import request from '@/utils/request'

// v1.1.90 QMS: 质量检验 (IQC/FAI/PAT/OQC/OQC_RETURN 合一)
// 门禁: quality:inspection:list / :add / :check + 类型后缀 :list/iqc / :check/iqc ...
// 超管 orRole 短路 (后端已配)

export const inspectionApi = {
  /** 列表分页, 支持 type/result/status/billDate 过滤 */
  page: (params) => request.get('/qms/inspection/page', { params }),
  /** 详情 (含缺陷明细) */
  detail: (id) => request.get(`/qms/inspection/${id}`),
  /** 创建草稿 (head + defects) */
  create: (payload) => request.post('/qms/inspection', payload),
  /** 审核 + 联动批次冻结 (result/disposition/reason 通过 body 传) */
  check: (id, body) => request.post(`/qms/inspection/${id}/check`, body),
  /** 拒收 (FAIL_REJECT + SCRAP) */
  reject: (id, reason) => request.post(`/qms/inspection/${id}/reject`, { reason }),
  /** v1.1.95 XK: 为采购入库单一步签署合格检验 (PASS), 放行入库 */
  signReceiptIqc: (receiptId, body) => request.post(`/qms/inspection/receipt/${receiptId}/sign`, body),
  // ===== v1.1.101 XK — 原料进货检验 (IQC) =====
  /** v1.1.101 — IQC 详情 (head + items + attachments + plan), App 端 pages/scan/in 主入口 */
  iqcDetail: (iqcId) => request.get(`/qms/iqc/${iqcId}/detail`),
  /** 列出某 IQC 全部检验项 */
  iqcItems: (iqcId) => request.get(`/qms/iqc/${iqcId}/items`),
  /** 全量替换某 IQC 的检验项 */
  iqcSaveItems: (iqcId, items) => request.put(`/qms/iqc/${iqcId}/items`, items),
  /** 列出某 IQC 全部附件/照片 */
  iqcAttachments: (iqcId) => request.get(`/qms/iqc/${iqcId}/attachments`),
  /** 标记附件已核验 */
  iqcVerifyAttachment: (id) => request.post(`/qms/iqc/attachment/${id}/verify`),
  /**
   * v1.1.131 — 幂等灌入服务器检验项模板 (OQC/OQC_RETURN 成品检, FAI/PAT 工序检), 回传 {head, items}.
   * 老草稿首次打开录入面板时调一次, 之后重复调无副作用.
   */
  ensureTemplate: (id) => request.post(`/qms/inspection/${id}/ensure-template`),
  /** v1.1.131 — 找/建某成品入库单的 OQC 草稿 + 灌成品检验模板, 回传 {head, items, finishedIn} */
  ensureOqcForFinishedIn: (finishedInId) => request.post('/qms/inspection/ensure-oqc-for-finished-in', { finishedInId }),
  /** 采购计划触发建/复用 IQC */
  iqcCreateFromPlan: (planId) => request.post('/qms/inspection/create-from-plan', { planId }),
  /** 签署合格 + 自动建 DRAFT 入库单 (App 端"生成报告并自动入库" 走这里) */
  iqcSignForPlan: (body) => request.post('/qms/inspection/sign-for-plan', body),
  /** 从 IQC 自动建 DRAFT 采购入库单 */
  iqcCreateReceipt: (planId, iqcId) => request.post('/purchase/receipt/create-from-iqc', { planId, iqcId })
}

// 后续 v1.1.91: 质量报表
export const qualityReportApi = {
  /** 不良率 (按产品) — v1.1.91 实现 */
  defectRateByProduct: (params) => request.get('/qms/report/defect-rate', { params })
}
