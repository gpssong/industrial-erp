/**
 * v1.1.207: App 全链追溯时间线口径 (纯函数, 不依赖 uni / 路由, 可 node --test 直测)
 *
 * 与 pc-web/src/api/xk.js 的 traceTimeline() **同构** —— 站点顺序、去重、截断、徽章判定逐条对齐,
 * 改一处口径必须两边同改 (PC 侧口径在 pc-web/src/api/xk.js:124-186, 现场事故见 app/test/traceTimeline.test.mjs 头注释).
 *
 * 与 PC 的唯一差异: 这里只吐 `link: { key, id }`, 不写页面路径.
 * 路由与权限由 pages/xk/trace.vue 的 STATION_LINKS 表决定 —— 口径层不碰导航, 才能脱离 uni-app 测试.
 */

/** ISO LocalDateTime → 'YYYY-MM-DD HH:mm' (后端序列化成 2026-11-01T08:30:00) */
export function fmtDateTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 16)
}

const MAX_PER_KIND = 4

/** 用 · 拼接非空片段 (单号与投料单号/数量同行显示时用) */
function join(...parts) {
  return parts.filter(Boolean).join(' · ')
}

/**
 * @param {object} t 后端 /xk/trace/full 的 data (XkController FullTrace)
 * @returns {{ batchNo: string, stations: Array, complete: boolean }}
 */
export function buildTraceStations(t) {
  const raw = t || {}
  const stations = []
  // 站点序号由这里统一发, 追加一类节点不用再各写一遍 padStart
  const push = (s) => stations.push({
    no: String(stations.length + 1).padStart(2, '0'),
    operator: '', time: '', remark: '', status: 'DONE', link: null, ...s
  })
  const take = (list) => (list || []).slice(0, MAX_PER_KIND)

  take(raw.upstreamBatches).forEach(bn => push({ title: '上游原料批', remark: bn || '' }))

  const batch = raw.batch || null
  push({
    title: '当前批次',
    operator: (batch && batch.batchType) || '',
    time: batch && (batch.productionDate || batch.createTime) ? fmtDateTime(batch.productionDate || batch.createTime) : '',
    remark: raw.batchNo || ''
  })

  const orderNos = new Set()
  take(raw.orders).forEach(o => {
    if (o.orderNo) orderNos.add(o.orderNo)
    push({
      title: '生产工单',
      operator: o.productName || '',
      remark: join(o.orderNo, o.issueBillNo && '投料单 ' + o.issueBillNo),
      link: o.orderId ? { key: 'order', id: String(o.orderId) } : null
    })
  })

  // 本厂成品批号就是工单号, 与上一站同名的不重复占一格; 半成品卷批号靠 downstreamOrderIds 找回工单
  const orderIds = raw.downstreamOrderIds || {}
  take((raw.downstreamBatches || []).filter(bn => !orderNos.has(bn))).forEach(bn => push({
    title: '下游流向批',
    remark: bn || '',
    link: orderIds[bn] ? { key: 'order', id: String(orderIds[bn]) } : null
  }))

  // 客户出口: 有出库行就逐单展开 (一批料可分批发给多个客户), 没有才退回标量客户
  if ((raw.deliveries || []).length) {
    take(raw.deliveries).forEach(d => push({
      title: '销售出库',
      operator: d.customerName || (d.customerId != null ? '客户#' + d.customerId : ''),
      time: fmtDateTime(d.time),
      remark: join(d.deliveryBillNo, d.qty != null && '数量 ' + d.qty),
      link: d.deliveryId ? { key: 'delivery', id: String(d.deliveryId) } : null
    }))
  } else if (raw.customer != null) {
    // 没有出库单可点, 这一站只能是纯文本
    push({ title: '流向客户', remark: raw.customerName || ('客户#' + raw.customer) })
  }

  return {
    batchNo: raw.batchNo || '',
    stations,
    // 徽章不能再无条件说「链路完整」: 桥接后仍没有客户出口 = 这批料还没卖出去
    complete: (raw.customer != null) || (raw.deliveries || []).length > 0
  }
}
