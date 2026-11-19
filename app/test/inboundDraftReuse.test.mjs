// v1.1.203 回归护栏: 「审核入库」重试不该每次都新建一张入库单草稿。
//
// 事故: v1.1.202 修好「入库单草稿保存失败」后, 审核被门禁拦住时 (工序未完工/OQC 未放行),
// 现场点一次「审核入库」就多一张 PFI 草稿 —— 同一工单下堆出一串废草稿
// (实测线上 PD202611030002 一次失败重试链留下 PFI202611190001/002/005 三张)。
//
// 口径: 同一工单 + DRAFT 状态的入库单只保留一张, 重试时复用它, 不再新建。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { findReusableDraft } from '../src/utils/inboundDraft.js'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../src')

test('findReusableDraft 找到同工单的 DRAFT 单', () => {
  const recs = [
    { id: '1', prdOrderId: '999', billStatus: 'DRAFT', billNo: 'PFI-A' },
    { id: '2', prdOrderId: '100', billStatus: 'DRAFT', billNo: 'PFI-B' },
    { id: '3', prdOrderId: '100', billStatus: 'CHECKED', billNo: 'PFI-C' }
  ]
  const hit = findReusableDraft(recs, '100')
  assert.ok(hit)
  assert.equal(hit.id, '2')
  assert.equal(hit.billNo, 'PFI-B')
})

test('findReusableDraft 忽略已审核单与别的工单', () => {
  const recs = [
    { id: '1', prdOrderId: '100', billStatus: 'CHECKED', billNo: 'PFI-C' },
    { id: '2', prdOrderId: '200', billStatus: 'DRAFT', billNo: 'PFI-D' }
  ]
  assert.equal(findReusableDraft(recs, '100'), null)
  assert.equal(findReusableDraft(recs, ''), null)
  assert.equal(findReusableDraft(null, '100'), null)
})

test('findReusableDraft 数量不一致时不复用 (入库单无改单接口)', () => {
  const recs = [{ id: '2', prdOrderId: '100', billStatus: 'DRAFT', billNo: 'PFI-B', qty: 2000 }]
  assert.equal(findReusableDraft(recs, '100', 2000).id, '2')
  assert.equal(findReusableDraft(recs, '100', 1500), null)
})

test('入库页 ensureDraft 先复用同工单既有草稿再建新单', () => {
  for (const rel of ['pages/scan/finished-in.vue', 'pages/scan/semi-in.vue']) {
    const src = readFileSync(resolve(root, rel), 'utf8')
    const start = src.indexOf('async ensureDraft()')
    assert.ok(start > 0, `${rel}: 找不到 ensureDraft()`)
    const body = src.slice(start, start + 2200)
    assert.ok(body.includes('findReusableDraft'),
      `${rel}: ensureDraft 未复用同工单既有草稿 (重试会堆废单)`)
    // 复用判定必须落在「新建接口调用」之前
    const reuseAt = body.indexOf('findReusableDraft')
    const addAt = body.indexOf('await api.prd')
    assert.ok(reuseAt > 0 && addAt > 0 && reuseAt < addAt,
      `${rel}: 复用既有草稿的判断必须在建单之前`)
  }
})
