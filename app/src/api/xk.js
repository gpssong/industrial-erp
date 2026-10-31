/**
 * H5 移动端 XK 合规 (XK16-204) — 后端接入
 * 与 pc-web/src/api/xk.js 同源但独立 (uni-app 不能直接 import pc-web 的 axios).
 * 各具名函数调后端 /xk/* 端点, 适配成各 app 页约定的返回 shape, 页面文件 0 改动.
 *
 * app 端 request (api/index.js) resolve 的是 R.data (已解包), 此处再包回各 app 页
 * 约定形态 ({code,data} / {code,items} / 顶层字段).
 */
import { request } from './index'
import { photosFromJson } from '../utils/photo.js'

// 统一: 成功返回 {code:200, ...}; 失败 {code:500, msg}
function ok(data, extra = {}) { return Object.assign({ code: 200, data }, extra) }
function fail(msg = '加载失败', data = null) { return { code: 500, msg, data } }

/** ISO 时间 → 'YYYY-MM-DD HH:mm' (后端 LocalDateTime 序列化成 2026-10-31T09:30:00) */
function fmtDateTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 16)
}

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
    // v1.1.147: 方向必须真的传到后端 (旧版 load() 不带参 → 两个 tab 列表一模一样);
    //   查询串写在 URL 上, 不依赖 GET data 序列化 (H5 fetch 回退会丢 data 参数).
    const batchType = direction === 'FG_TO_RM' ? 'FG' : 'RAW'
    const data = await request({
      url: '/xk/trace/batches?batchType=' + batchType,
      method: 'GET'
    })
    const list = (data || []).map(b => ({
      batchNo: b.batchNo,
      batchType: b.batchType,
      // mes_batch 只有来源单号, 没有供应商/商品名 —— 如实展示来源单据
      source: b.receiptBillNo ? '入库单 ' + b.receiptBillNo
        : b.sourcePrdOrderNo ? '工单 ' + b.sourcePrdOrderNo
          : (b.processName || ''),
      qty: b.qty ?? null,
      date: b.productionDate || fmtDateTime(b.createTime),
      status: b.status || 'ACTIVE',
      holdReason: b.holdReason || ''
    }))
    return ok(list)
  } catch (e) { return fail(e.msg || '加载失败', []) }
}

/**
 * v1.1.147: 全链追溯 (料→半成品→成品→客户, 服务端一次展开).
 * 旧版用 /xk/trace/{batchNo} 只取上下游各 2 条, 且把用户 id 当操作人显示 —— 已废弃.
 */
export const traceFull = async (batchNo) => {
  try {
    const t = await request({ url: '/xk/trace/full/' + encodeURIComponent(batchNo), method: 'GET' })
    if (!t) return fail('该批次暂无追溯记录')
    const mapLink = (l) => ({
      parentBatchNo: l.parentBatchNo || '',
      childBatchNo: l.childBatchNo || '',
      qty: l.qty ?? null,
      billType: l.billType || '',
      billNo: l.billNo || '',
      time: fmtDateTime(l.createTime)
    })
    return ok({
      batchNo: t.batchNo || batchNo,
      batch: t.batch ? {
        batchNo: t.batch.batchNo,
        batchType: t.batch.batchType,
        status: t.batch.status || 'ACTIVE',
        holdReason: t.batch.holdReason || '',
        qty: t.batch.qty ?? null,
        date: t.batch.productionDate || fmtDateTime(t.batch.createTime),
        sourcePrdOrderNo: t.batch.sourcePrdOrderNo || '',
        receiptBillNo: t.batch.receiptBillNo || '',
        processName: t.batch.processName || '',
        remark: t.batch.remark || ''
      } : null,
      upstream: (t.upstream || []).map(mapLink),
      downstream: (t.downstream || []).map(mapLink),
      customer: t.customer ?? null,
      customerName: t.customerName || ''
    })
  } catch (e) { return fail(e.msg || '加载失败') }
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

// ===== v1.1.147 单据打印: 已审核采购入库单 =====

/** 可打印的入库单 (仅已审核 CHECKED, 与 PC「单据与打印」同口径). */
export const receiptList = async () => {
  try {
    const page = await request({ url: '/xk/material/page?pageNum=1&pageSize=50&billStatus=CHECKED', method: 'GET' })
    const list = (page?.records || []).map(r => ({
      id: r.id,
      billNo: r.billNo,
      supplier: r.supplierName || '-',
      billDate: r.billDate || '',
      warehouse: r.warehouseName || '-',
      label: [r.billNo, r.supplierName || '-', r.billDate || ''].filter(Boolean).join(' · ')
    }))
    return ok(list)
  } catch (e) { return fail(e.msg || '加载失败', []) }
}

/** 入库单详情 (含明细 + 标签照片 + 制单人/检验员). */
export const receiptDetail = async (id) => {
  if (id == null) return fail('未选择入库单')
  try {
    const r = await request({ url: '/xk/material/' + id, method: 'GET' })
    if (!r) return fail('入库单不存在')
    return ok({
      id: r.id,
      billNo: r.billNo || '',
      supplier: r.supplierName || '-',
      billDate: r.billDate || '',
      warehouse: r.warehouseName || '-',
      operator: r.createByName || '',
      inspector: r.inspectorName || '',
      reportNo: r.inspectionReportNo || '',
      inspectionResult: r.inspectionResult || '',
      remark: r.remark || '',
      status: r.billStatus || '',
      totalQty: r.totalQty ?? null,
      photos: photosFromJson(r.labelPhotos),
      details: (r.details || []).map(x => ({
        productName: x.productName || '-',
        batchNo: x.batchNo || '',
        qty: x.qty ?? null,
        unitName: x.unitName || '',
        location: x.locationName || x.location || '',
        productionDate: x.productionDate || ''
      }))
    })
  } catch (e) { return fail(e.msg || '加载失败') }
}

/** 打印留痕 (XK 审计表). 无 xk:audit:archive 的账号会 403 —— 静默忽略, 不影响打印. */
export const printAudit = async (billNo) => {
  try {
    await request({ url: '/xk/print/audit', method: 'POST', data: { billNo }, contentType: 'json', silent: true })
    return ok(true)
  } catch (e) { return { code: 500, msg: e.msg || '审计登记失败' } }
}

export default {
  dashboardKpi, materialDraft, submitMaterial, traceBatches, traceFull,
  maintenanceOrders, safetyCheck, auditLogs, archivedRecords, equipmentInfo,
  printPreview, receiptList, receiptDetail, printAudit
}
