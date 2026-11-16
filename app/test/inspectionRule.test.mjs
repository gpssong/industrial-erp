// 需求: 首检 (FAI) 现场「全点合格」却签不下来 —— 提示「请完成全部检验项的记录及明确判定」。
// 根因: 客户端/服务端都要求**每一项**都必须有实测值文本, 而定性项 (unit 为空: 原料核对/膜面外观/
// 异味/清洁与异物控制) 现场只点判定、不填文本 (已签核的 IQC 单据就是这么做的)。
// 口径: 定量项 (unit 非空: 厚度 mm / 宽度 mm) 必须填实测值; 定性项明确判定即可。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isMeasuredItem, missingInspectionItems, describeMissingInspectionItems } from '../src/utils/inspectionRule.js'

const item = (over) => Object.assign({ itemCode: 'X', itemName: '某检验项', unit: null, passFlag: 1, resultValue: '' }, over)

test('unit 非空即定量项, 需实测值; unit 为空即定性项', () => {
  assert.equal(isMeasuredItem(item({ unit: 'mm' })), true)
  assert.equal(isMeasuredItem(item({ unit: null })), false)
  assert.equal(isMeasuredItem(item({ unit: '  ' })), false)
  assert.equal(isMeasuredItem(null), false)
})

test('定性项只判定即可, 不算未完成', () => {
  const items = [item({ itemCode: 'BLOW_ODOR', unit: null, passFlag: 1, resultValue: '' })]
  assert.deepEqual(missingInspectionItems(items), [])
  assert.equal(describeMissingInspectionItems(items), '')
})

test('定量项缺实测值算未完成', () => {
  const items = [item({ itemCode: 'BLOW_THICKNESS', itemName: '厚度偏差（表3）', unit: 'mm', passFlag: 1, resultValue: '' })]
  const missing = missingInspectionItems(items)
  assert.equal(missing.length, 1)
  assert.equal(missing[0].reason, '缺实测值')
  assert.match(describeMissingInspectionItems(items), /厚度偏差/)
})

test('待检 (-1) 与未判定都算未完成', () => {
  const items = [item({ itemCode: 'A', unit: null, passFlag: -1 })]
  assert.equal(missingInspectionItems(items)[0].reason, '未判定')
  const noFlag = [item({ itemCode: 'A', unit: null, passFlag: null })]
  assert.equal(missingInspectionItems(noFlag)[0].reason, '未判定')
})

test('不适用 (2) 一律不算未完成', () => {
  const items = [item({ itemCode: 'BAG_PRINT', unit: 'mm', passFlag: 2, resultValue: '' })]
  assert.deepEqual(missingInspectionItems(items), [])
})

test('提示语点名是哪几项', () => {
  const items = [
    item({ itemCode: 'A', itemName: '厚度偏差（表3）', unit: 'mm', passFlag: 1, resultValue: '' }),
    item({ itemCode: 'B', itemName: '原料核对（3.5）', unit: null, passFlag: -1, resultValue: '' })
  ]
  const msg = describeMissingInspectionItems(items)
  assert.match(msg, /厚度偏差（表3）/)
  assert.match(msg, /原料核对（3.5）/)
  assert.match(msg, /缺实测值/)
  assert.match(msg, /未判定/)
})
