/**
 * XK 合规中心 (XK16-204) — 后端接入 (方式 A: 复用 mes/workflow/em/purchase + modules/xk)
 *
 * 每个函数调用真实 /xk/* 端点, 并把后端字段 map 成视图约定的 mock shape,
 * 视图文件 0 改动。真实列表数据来自后端; 纯展示字段 (照片/操作人兜底) 保留占位。
 *
 * 端点 (backend modules/xk XkController):
 *   GET /xk/dashboard              KPI + 待办聚合
 *   GET /xk/material/page | {id}  原料入库 (PurReceiptService)
 *   GET /xk/trace/batches | {bn}  批次溯源 (MesReportService)
 *   GET /xk/maintenance/orders | equipment  设备维保 (WfMaintain + Equipment)
 *   GET /xk/safety/check          卫生检查 (xk_safety_check)
 *   GET /xk/print/preview | POST /xk/print/audit  单据打印 (审计 + SHA256)
 *   GET /xk/audit/logs | archive  审计归档 (xk_audit_log)
 */
import request from '@/utils/request'

// 兜底: 后端 404/结构缺失时给视图一个空结构, 避免渲染崩
const empty = (code = 200, data = null, message = '') => ({ code, data, message })

// v1.1.99: 照片占位已移除 (去 mock). 原料标签照片走 base_pur_receipt.labelPhotos (真实 /system/upload),
// 后端未回填时给空数组, 前端不渲染 unsplash 假图.
const PHOTO = null

export const xkMock = {
  // ===== Dashboard =====
  async dashboardKpi() {
    const r = await request.get('/xk/dashboard')
    if (r.code === 200 && r.data) {
      const d = r.data
      return empty(200, {
        // 4 KPI: 真实 ERP KPI (reportMapper.dashboardKpi) + 合规 KPI
        todayOutput: { value: d.erpKpi?.todaySales ?? 0, unit: '元', delta: '', trend: 'up' },
        equipmentRun: { value: 0, unit: '%', running: 0, total: 0 },
        pendingMaintain: { value: Number(d.compliance?.maintainPending ?? 0), overdue: 0, unit: '项' },
        stockWarning: { value: 0, unit: '种', note: '需及时补料' },
        // 产线 (真实批次列表 → 产线 mock, 一期不接实时产线)
        productionLines: (d.maintainTodos || []).slice(0, 3).map(m => ({
          id: m.recordNo, name: m.deviceName, product: m.maintType, workOrder: m.recordNo,
          progress: 0, status: m.billStatus
        })),
        // 待办: 维保 + 检验 聚合
        todos: [
          ...(d.maintainTodos || []).map(m => ({
            id: m.id, level: 'HIGH', bizType: 'MAINTAIN',
            title: '设备维保', subtitle: `${m.recordNo} · ${m.deviceName || ''}`, time: m.maintDate || ''
          })),
          ...(d.inspectTodos || []).map(i => ({
            id: i.id, level: i.result === 'FAIL' ? 'URGENT' : 'MID', bizType: 'INSPECT',
            title: i.inspectionType + ' 待审核', subtitle: i.billNo, time: i.createTime || ''
          }))
        ]
      })
    }
    return empty(200, null)
  },

  // ===== 原料入库 =====
  async materialDraft(id) {
    // 一期: XK 原料入库 = 采购入库单。无 id 时取最新 1 条 (billStatus=DRAFT)
    const params = id ? { pageNum: 1, pageSize: 1 } : { pageNum: 1, pageSize: 1, billStatus: 'DRAFT' }
    const url = id ? `/xk/material/${id}` : '/xk/material/page'
    const r = await (id ? request.get(url) : request.get(url, { params }))
    if (r.code === 200) {
      const raw = id ? r.data : (r.data?.records?.[0] || r.data?.data?.[0])
      if (raw) return empty(200, mapMaterial(raw))
      // 无 DRAFT 入库单时不能返回 null: Material.vue 直接 form.value = r.data,
      // 模板访问 form.location 等会崩 (Cannot read properties of null)。给一个完整空 form。
      return empty(200, {
        billNo: '', billDate: '', supplierId: null, inboundDate: '',
        inspectionReportNo: '', warehouseId: null, materialId: null,
        applicableProductLine: '', batchNo: '', qty: 0, location: '', labelPhotos: []
      })
    }
    return empty(500, null, r.msg)
  },

  async submitMaterialDraft(payload) {
    // 一期: XK 原料入库提交 = 采购入库单提交 (XK 合规固化: 走 PUR_RECEIPT 审核 + 审计)
    const r = await request.post('/xk/material', payload)
    return empty(r.code, r.data, r.msg || '已提交')
  },

  // ===== 批次溯源 =====
  // v1.1.99 C2 去 mock: 批次台账走真 mes_batch, 时间线走服务端全链 traceFull (料→半成品→成品→客户 一次展开).
  // 不再硬编码 unit:'kg' / customer:'-' 占位; 真实字段来自后端 FullTrace.
  async traceBatches(direction) {
    const params = {}
    if (direction === 'FG_TO_RM') params.batchType = 'FG'
    else params.batchType = 'RAW'
    const r = await request.get('/xk/trace/batches', { params })
    if (r.code === 200 && r.data) {
      return empty(200, r.data.map(b => ({
        batchNo: b.batchNo,
        batchType: b.batchType,
        productName: b.productName || (b.productId ? '#' + b.productId : '批次'),
        supplier: b.receiptBillNo ? '入库单 ' + b.receiptBillNo : (b.sourcePrdOrderNo ? '工单 ' + b.sourcePrdOrderNo : '-'),
        qty: b.qty ?? 0, unit: b.unitName || '',
        receiptDate: b.productionDate || b.createTime || '-',
        qcStatus: b.status || 'ACTIVE',
        // linked 列由 traceFull 时间线回填 (此处占位, 行点击时用真数据)
        linked: { fgBatch: b.sourcePrdOrderNo || '-', customer: '-', qty: 0 }
      })))
    }
    return empty(200, [])
  },

  async traceTimeline(batchNo) {
    // v1.1.99 C2: 全链追溯, 服务端一次展开 料→半成品→成品→客户 (带客户出口)
    const r = await request.get('/xk/trace/full/' + batchNo)
    if (r.code === 200 && r.data) {
      const t = r.data
      const stations = []
      // 上溯链 (料→半成品→成品): 逐批号展开, 用 real batchName/customerName 无占位
      ;(t.upstreamBatches || []).slice(0, 4).forEach(bn => {
        stations.push({ no: String(stations.length + 1).padStart(2, '0'), title: '上游原料批',
          operator: '', time: '', status: 'DONE', remark: bn || '', photo: null })
      })
      // 起点批次
      stations.push({ no: String(stations.length + 1).padStart(2, '0'), title: '当前批次',
        operator: (t.batch && t.batch.batchType) || '', time: (t.batch && (t.batch.productionDate || t.batch.createTime)) || '',
        status: 'DONE', remark: batchNo, photo: null })
      // 下探链 (成品→客户)
      ;(t.downstreamBatches || []).slice(0, 4).forEach(bn => {
        stations.push({ no: String(stations.length + 1).padStart(2, '0'), title: '下游流向批',
          operator: '', time: '', status: 'DONE', remark: bn || '', photo: null })
      })
      // 客户出口
      if (t.customer != null) {
        stations.push({ no: String(stations.length + 1).padStart(2, '0'), title: '流向客户',
          operator: '', time: '', status: 'DONE', remark: (t.customerName || ('客户#' + t.customer)), photo: null })
      }
      return empty(200, { batchNo, stations, customer: t.customer, customerName: t.customerName })
    }
    return empty(200, { batchNo, stations: [] })
  },
  // ===== 设备维保 =====
  // v1.1.99 附带清理: 后端 /xk/maintenance/orders 数据源切 em_maintain 新表 (EmMaintainOrder),
  // 与维保功能页同源. 字段映射: orderNo/equipmentName/maintContent/planDate/operator/status.
  async maintenanceOrders() {
    const r = await request.get('/xk/maintenance/orders', { params: { pageNum: 1, pageSize: 10 } })
    if (r.code === 200 && r.data?.records) {
      return empty(200, r.data.records.map(o => ({
        id: o.id, recordNo: o.orderNo,
        // 真实 em 工单: PENDING(未处理) 视为紧急, 其余中优先级
        priority: o.status === 'PENDING' ? 'URGENT' : 'MID',
        deviceCode: o.equipmentCode ? '#' + o.equipmentCode : (o.equipmentId ? '#' + o.equipmentId : ''),
        deviceName: o.equipmentName || '',
        workOrder: o.maintContent || o.sourceType || '-',
        problem: o.result || o.description || o.remark || '',
        reportTime: o.planDate ? String(o.planDate) + ' 00:00:00' : '',
        reporter: o.operator || '',
        billStatus: o.status
      })))
    }
    return empty(200, [])
  },

  async equipmentInfo(deviceCode) {
    if (!deviceCode) return empty(200, null)
    // 视图传的是 o.deviceCode (即 #id 或空) → 后端按 code 查
    const r = await request.get('/xk/maintenance/equipment', { params: { code: deviceCode.replace(/^#/, '') || deviceCode } })
    if (r.code === 200 && r.data) {
      const e = r.data
      return empty(200, {
        code: e.equipmentCode, name: e.equipmentName, type: e.equipmentType,
        model: e.model, manufacturer: e.manufacturer, installDate: e.installDate,
        location: e.location, workshop: e.workshop,
        totalRunHours: 0, lastMaint: e.lastMaintDate, nextMaint: e.nextMaintDate,
        status: e.status
      })
    }
    return empty(200, null)
  },

  // ===== 卫生检查 =====
  async safetyAudit() {
    const r = await request.get('/xk/safety/check', { params: { pageNum: 1, pageSize: 1 } })
    if (r.code === 200 && r.data?.records?.length) {
      const c = r.data.records[0]
      return empty(200, { recordNo: c.checkNo, ...c })
    }
    // 无卫生检查单时不能返回 null: Safety.vue 直接 recordNo.value = r.data.recordNo,
    // 若 data=null 则模板 {{ recordNo }} 崩。给一个完整空 shape (走默认 recordNo)。
    return empty(200, {
      recordNo: '', checkDate: '', area: '', inspector: '',
      result: 'PASS', itemSnapshot: '[]', reformOwner: '', reformDeadline: '',
      billStatus: ''
    })
  },

  // ===== 单据打印 =====
  async printPreview() {
    const r = await request.get('/xk/print/preview')
    return empty(r.code, r.data || {}, r.msg)
  },

  async printAudit(payload) {
    const r = await request.post('/xk/print/audit', payload)
    return empty(r.code, r.data, r.msg)
  },

  // ===== 审计日志 / 归档 =====
  async auditLogs() {
    const r = await request.get('/xk/audit/logs', { params: { limit: 50 } })
    if (r.code === 200 && r.data) {
      // Audit.vue 用 row.operator.split(' / ') 拆出 姓名 + 角色, 故拼成 "姓名 / 角色" 格式
      return empty(200, r.data.map(l => ({
        id: l.id, time: l.operTime,
        operator: (l.operatorName || '未知') + ' / ' + (l.operatorRole || '未知'),
        action: l.action,
        billNo: l.bizBillNo || '', change: l.changeDesc || ''
      })))
    }
    return empty(200, [])
  },

  async archivedRecords() {
    const r = await request.get('/xk/audit/archive')
    if (r.code === 200 && r.data) {
      return empty(200, r.data.map(l => ({
        id: l.id, billNo: l.bizBillNo, bizType: l.bizType,
        createTime: l.operTime, createBy: l.operatorName, sizeKb: l.sizeKb ?? 0,
        ossPath: l.ossPath || '', expireAt: l.expireAt
      })))
    }
    return empty(200, [])
  },

  // ===== 用户列表 (沿用原 ERP, 不走 /xk, 直接系统用户 API) =====
  async userList() {
    const r = await request.get('/system/user/page', { params: { pageNum: 1, pageSize: 50 } })
    if (r.code === 200 && r.data?.records) {
      return empty(200, r.data.records.map(u => ({
        id: u.id, name: u.nickname || u.username, account: u.username,
        initial: (u.nickname || u.username || '').slice(0, 1),
        role: '', roleKey: '', dept: '', phone: u.phone || '',
        lastLogin: '', status: u.status === 1 ? '正常' : '停用'
      })))
    }
    return empty(200, [])
  }
}

// 采购入库单 → XK 原料入库 mock shape
function mapMaterial(raw) {
  const d = raw.details?.[0] || {}
  return {
    id: raw.id,
    billNo: raw.billNo,
    billDate: raw.billDate,
    supplierId: raw.supplierId,
    supplierName: raw.supplierName,
    inboundDate: raw.billDate,
    inspectionReportNo: raw.inspectionReportNo || ('JY-' + String(raw.billNo || '')),
    warehouseId: raw.warehouseId,
    warehouseName: raw.warehouseName,
    materialName: d.productName || raw.productName || '原料',
    materialId: d.productId,
    applicableProductLine: '食品袋',
    batchNo: d.batchNo || raw.batchNo || '',
    qty: d.qty ?? 0,
    location: d.location || '-',
    // v1.1.99 去 mock: 只保留真实标签照片, 无则空数组 (不再注 unsplash 假图)
    labelPhotos: raw.labelPhotos?.length ? raw.labelPhotos : []
  }
}

// v1.1.97+ XK 生产环境检查 (Safety.vue Tab 3) — 直接走真后端
export const envCheckApi = {
  page:   (params) => request.get('/xk/env-check/page', { params }),
  detail: (id)     => request.get(`/xk/env-check/${id}`),
  save:   (data)   => request.post('/xk/env-check', data),
  check:  (id, body) => request.post(`/xk/env-check/${id}/check`, body),
  delete: (id)     => request.delete(`/xk/env-check/${id}`)
}

export default xkMock
