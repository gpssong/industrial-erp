/**
 * H5 移动端 XK 合规 (XK16-204) — 后端接入
 * 与 pc-web/src/api/xk.js 同源但独立 (uni-app 不能直接 import pc-web 的 axios).
 * 各具名函数调后端 /xk/* 端点, 适配成各 app 页约定的返回 shape, 页面文件 0 改动.
 *
 * app 端 request (api/index.js) resolve 的是 R.data (已解包), 此处再包回各 app 页
 * 约定形态 ({code,data} / {code,items} / 顶层字段).
 */
import { request } from './index'

// 统一: 成功返回 {code:200, ...}; 失败 {code:500, msg}
function ok(data, extra = {}) { return Object.assign({ code: 200, data }, extra) }
function fail(msg = '加载失败', data = null) { return { code: 500, msg, data } }

export const dashboardKpi = async () => {
  try {
    const d = await request({ url: '/xk/dashboard', method: 'GET' })
    return ok({
      todayOutput: { value: d?.erpKpi?.todaySales ?? 0, unit: '元', delta: '' },
      equipmentRun: { value: 0, running: 0, total: 0 },
      pendingMaintain: { value: Number(d?.compliance?.maintainPending ?? 0), overdue: 0, unit: '项' },
      stockWarning: { value: 0, unit: '种', note: '需及时补料' },
      productionLines: (d?.maintainTodos || []).slice(0, 3).map(m => ({
        id: m.recordNo, name: m.deviceName, product: m.maintType,
        wo: m.recordNo, progress: 0, status: m.billStatus
      })),
      todos: [
        ...(d?.maintainTodos || []).map(m => ({
          id: m.id, level: 'HIGH', bizType: 'MAINTAIN',
          title: '设备维保', subtitle: `${m.recordNo} · ${m.deviceName || ''}`, time: m.maintDate || ''
        })),
        ...(d?.inspectTodos || []).map(i => ({
          id: i.id, level: i.result === 'FAIL' ? 'URGENT' : 'MID', bizType: 'INSPECT',
          title: (i.inspectionType || '') + ' 待审核', subtitle: i.billNo, time: i.createTime || ''
        }))
      ]
    })
  } catch (e) { return fail(e.msg || '加载失败') }
}

export const materialDraft = async () => {
  try {
    const list = await request({ url: '/xk/material/page', method: 'GET', data: { pageNum: 1, pageSize: 1, billStatus: 'DRAFT' } })
    const raw = list?.records?.[0]
    if (!raw) return ok({ billNo: '', supplier: '', product: '', batchNo: '', qty: 0, location: '', reportNo: '', warehouse: '', productLine: '', labelPhotos: '' })
    const d = raw.details?.[0] || {}
    // app material.vue 读顶层字段 (r.billNo / r.supplier / ...)
    return ok({
      billNo: raw.billNo,
      supplier: raw.supplierName || '-',
      product: d.productName || raw.productName || '原料',
      batchNo: d.batchNo || raw.batchNo || '',
      qty: d.qty ?? 0,
      location: d.location || '-',
      reportNo: 'JY-' + String(raw.billNo || ''),
      warehouse: raw.warehouseName || '-',
      productLine: '食品袋',
      // v1.1.106: 原料标签照片 (PurReceipt.labelPhotos, JSON URL 数组)
      labelPhotos: raw.labelPhotos || '',
      // v1.1.106: 打印页需要完整明细 + 制单/检验/审核人
      billDate: raw.billDate || '',
      operator: raw.createByName || raw.operatorName || '',
      iqcInspector: raw.iqcInspectorName || '',
      checker: raw.checkByName || raw.updateByName || '',
      details: (raw.details || []).map(x => ({
        productName: x.productName || '-',
        batchNo: x.batchNo || '',
        qty: x.qty ?? 0,
        unitName: x.unitName || '',
        location: x.location || '',
        iqcResult: x.iqcResult || ''
      }))
    })
  } catch (e) { return fail(e.msg || '加载失败') }
}

export const submitMaterial = async (payload) => {
  try {
    await request({ url: '/xk/material', method: 'POST', data: payload, contentType: 'json' })
    return { code: 200, message: '已提交入库, 自动建草稿' }
  } catch (e) { return fail(e.msg || '提交失败') }
}

export const traceBatches = async (direction) => {
  try {
    const data = await request({
      url: '/xk/trace/batches',
      method: 'GET',
      data: { batchType: direction === 'FG_TO_RM' ? 'FG' : 'RAW' }
    })
    const list = (data || []).map(b => ({
      batchNo: b.batchNo,
      batchType: b.batchType,
      productName: b.productName || (b.productId ? '#' + b.productId : '原料批次'),
      supplier: b.receiptBillNo ? '入库单 ' + b.receiptBillNo : '-',
      qty: b.qty ?? 0,
      unit: 'kg',
      receiptDate: b.productionDate || '-',
      qcStatus: b.status || 'ACTIVE'
    }))
    return ok(list)
  } catch (e) { return fail(e.msg || '加载失败', []) }
}

export const traceTimeline = async (batchNo) => {
  try {
    const t = await request({ url: '/xk/trace/' + batchNo, method: 'GET' })
    const stations = []
    ;(t?.upstream || []).slice(0, 2).forEach(l => {
      stations.push({ title: '上游 (用了哪些料)', operator: l.createBy || '', time: l.createTime || '', remark: l.parentBatchNo || '' })
    })
    ;(t?.downstream || []).slice(0, 2).forEach(l => {
      stations.push({ title: '下游 (流向哪)', operator: l.createBy || '', time: l.createTime || '', remark: l.childBatchNo || '' })
    })
    return { code: 200, batchNo, stations }
  } catch (e) { return { code: 500, msg: e.msg || '加载失败', batchNo, stations: [] } }
}

export const maintenanceOrders = async () => {
  try {
    const page = await request({ url: '/xk/maintenance/orders', method: 'GET', data: { pageNum: 1, pageSize: 50 } })
    // v1.1.102 hotfix-4: 直接透传 em_maintain_order 真实字段 (EmMaintainOrder:
    // id/orderNo/equipmentCode/equipmentName/maintContent/planDate/status/sourceType/photoUrls/description/operator)
    // 旧版映射成 mock 字段 (recordNo/deviceCode/...) 导致前端模板读真实字段全空.
    const list = page?.records || []
    return ok(list)
  } catch (e) { return fail(e.msg || '加载失败', []) }
}

export const safetyCheck = async () => {
  try {
    const page = await request({ url: '/xk/safety/check', method: 'GET', data: { pageNum: 1, pageSize: 1 } })
    const c = page?.records?.[0]
    // app safety.vue 读 r.items (5 项 status=OK/PROBLEM + text)
    let items = []
    try { items = c?.itemSnapshot ? JSON.parse(c.itemSnapshot) : [] } catch (_) { items = [] }
    // v1.1.106: 一并回显整改前后照片 + 整改责任人/期限
    return Object.assign({ code: 200, recordNo: c?.checkNo || '' }, {
      items,
      photosBefore: c?.reformPhotosBefore || '',
      photosAfter: c?.reformPhotosAfter || '',
      reformOwner: c?.reformOwner || '',
      reformDeadline: c?.reformDeadline || ''
    })
  } catch (e) { return fail(e.msg || '加载失败') }
}

export const auditLogs = async () => {
  try {
    const data = await request({ url: '/xk/audit/logs', method: 'GET', data: { limit: 50 } })
    const actionLabel = { PDF: '生成 PDF', EDIT: '修改', UPLOAD: '上传附件', VOID: '作废' }
    const list = (data || []).map(l => ({
      id: l.id, time: l.operTime,
      operator: (l.operatorName || '未知') + ' / ' + (l.operatorRole || '未知'),
      action: actionLabel[l.action] || l.action,
      billNo: l.bizBillNo || '', change: l.changeDesc || ''
    }))
    return ok(list)
  } catch (e) { return fail(e.msg || '加载失败', []) }
}

export const archivedRecords = async () => {
  try {
    const data = await request({ url: '/xk/audit/archive', method: 'GET' })
    const list = (data || []).map(l => ({
      id: l.id, billNo: l.bizBillNo, bizType: l.bizType,
      createTime: l.operTime, createBy: l.operatorName, sizeKb: l.sizeKb ?? 0,
      ossPath: l.ossPath || '', expireAt: l.expireAt
    }))
    return ok(list)
  } catch (e) { return fail(e.msg || '加载失败', []) }
}

export const equipmentInfo = async (deviceCode) => {
  if (!deviceCode) return ok(null)
  try {
    const e = await request({ url: '/xk/maintenance/equipment', method: 'GET', data: { code: String(deviceCode).replace(/^#/, '') || deviceCode } })
    return ok(e ? {
      code: e.equipmentCode, name: e.equipmentName, type: e.equipmentType,
      model: e.model, manufacturer: e.manufacturer, installDate: e.installDate,
      location: e.location, workshop: e.workshop,
      totalRunHours: 0, lastMaint: e.lastMaintDate, nextMaint: e.nextMaintDate, status: e.status
    } : null)
  } catch (err) { return fail(err.msg || '加载失败') }
}

export const printPreview = async () => {
  try {
    const d = await request({ url: '/xk/print/preview', method: 'GET' })
    return ok(d || {})
  } catch (e) { return fail(e.msg || '加载失败') }
}

export default {
  dashboardKpi, materialDraft, submitMaterial, traceBatches, traceTimeline,
  maintenanceOrders, safetyCheck, auditLogs, archivedRecords, equipmentInfo, printPreview
}
