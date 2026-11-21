// v1.1.207: App 全链追溯时间线口径护栏 — 与 pc-web/src/api/xk.js:124-186 的 traceTimeline() 同构.
//
// 现场要点 (v1.1.205/206 已给 PC 解决, 这次轮到车间手机):
//   ① 投料那一刻 mes_batch_link 没有下游批号, 批次链是断的 —— 生产工单是把「原料批 → 成品批」
//      接起来的唯一一座桥, 所以工单必须占一站, 不能只显示批号;
//   ② 本厂成品批号就是工单号, 下游批里同名的不重复占一格;
//   ③ 一批料可分批发给多个客户, 出库要逐单展开, 且要能点进出库单;
//   ④ 桥接后仍没有客户出口 = 这批料还没卖出去, 徽章不许说「链路完整」;
//   ⑤ 服务端没给 id 就不能生成链接 —— 死按钮比没按钮更糟.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildTraceStations } from '../src/utils/traceTimeline.js'

const raw = (over = {}) => ({
  batchNo: 'WQ20261101',
  batch: { batchType: 'RAW', createTime: '2026-11-01T08:30:00' },
  ...over
})

const titles = (s) => s.map((x) => x.title)

test('原料批在投料处断链时, 生产工单要占一站并带工单号与投料单号', () => {
  const r = buildTraceStations(raw({
    orders: [{ orderId: '1943', orderNo: 'PD202611030001', productName: '金博23*29*0.12', issueBillNo: 'LL202611030001' }]
  }))
  assert.deepEqual(titles(r.stations), ['当前批次', '生产工单'])
  const wo = r.stations[1]
  assert.equal(wo.operator, '金博23*29*0.12')
  assert.equal(wo.remark, 'PD202611030001 · 投料单 LL202611030001')
  assert.deepEqual(wo.link, { key: 'order', id: '1943' })
  assert.equal(r.complete, false, '没有客户出口就不算链路完整')
})

test('下游批与本厂工单同名时不重复占一格 (成品批号就是工单号)', () => {
  const r = buildTraceStations(raw({
    orders: [{ orderId: '1943', orderNo: 'PD202611030001' }],
    downstreamBatches: ['PD202611030001', 'SEMI20261103A']
  }))
  assert.deepEqual(titles(r.stations), ['当前批次', '生产工单', '下游流向批'])
  assert.equal(r.stations[2].remark, 'SEMI20261103A')
})

test('桥接出来的半成品批要带产出它的工单 id, 否则只当文本', () => {
  const r = buildTraceStations(raw({
    downstreamBatches: ['SEMI20261103A', 'SEMI20261103B'],
    downstreamOrderIds: { SEMI20261103A: '2001' }
  }))
  assert.deepEqual(r.stations[1].link, { key: 'order', id: '2001' })
  assert.equal(r.stations[2].link, null, 'downstreamOrderIds 查不到的批次不许给死链接')
})

test('一批料分批发给多个客户时, 出库逐单展开且每单可点', () => {
  const r = buildTraceStations(raw({
    deliveries: [
      { deliveryBillNo: 'CK001', deliveryId: '7001', customerName: '舟山鹏程甲', qty: 120, time: '2026-11-10T14:05:00' },
      { deliveryBillNo: 'CK002', deliveryId: '7002', customerName: '宁波乙', qty: 80, time: '2026-11-12T09:40:00' }
    ]
  }))
  const outs = r.stations.filter((s) => s.title === '销售出库')
  assert.equal(outs.length, 2)
  assert.equal(outs[0].operator, '舟山鹏程甲')
  assert.equal(outs[0].time, '2026-11-10 14:05')
  assert.equal(outs[0].remark, 'CK001 · 数量 120')
  assert.deepEqual(outs[1].link, { key: 'delivery', id: '7002' })
  assert.equal(r.complete, true)
})

test('没有出库单但服务端回了标量客户时, 退回纯文本「流向客户」站', () => {
  const r = buildTraceStations(raw({ customer: '812', customerName: '定海丙' }))
  assert.deepEqual(titles(r.stations), ['当前批次', '流向客户'])
  assert.equal(r.stations[1].remark, '定海丙')
  assert.equal(r.stations[1].link, null)
  assert.equal(r.complete, true)
})

test('出库行缺 deliveryId 时仍显示单号, 只是不给链接', () => {
  const r = buildTraceStations(raw({ deliveries: [{ deliveryBillNo: 'CK009', qty: 5 }] }))
  assert.equal(r.stations[1].remark, 'CK009 · 数量 5')
  assert.equal(r.stations[1].link, null)
})

test('上游原料批逐条展开, 空字段不崩', () => {
  const r = buildTraceStations(raw({ upstreamBatches: ['YL001', 'YL002'] }))
  assert.deepEqual(titles(r.stations), ['上游原料批', '上游原料批', '当前批次'])
  assert.equal(r.stations[0].remark, 'YL001')
  assert.equal(r.stations[1].remark, 'YL002')
  const empty = buildTraceStations({})
  assert.deepEqual(titles(empty.stations), ['当前批次'])
  assert.equal(empty.complete, false)
})

test('站点编号连续补零, 每类最多 4 条', () => {
  const many = Array.from({ length: 6 }, (_, i) => ({ orderId: String(i), orderNo: 'PD' + i }))
  const r = buildTraceStations(raw({ orders: many }))
  assert.deepEqual(r.stations.map((s) => s.no), ['01', '02', '03', '04', '05'])
  assert.equal(r.stations.filter((s) => s.title === '生产工单').length, 4)
})
