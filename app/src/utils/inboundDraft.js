// v1.1.203 — 入库单草稿复用口径 (成品入库 / 半成品入库 共用)。
//
// 背景: App「审核入库」是一步完成 (没草稿就现建一张再审核)。门禁拦截 (工序未完工 / OQC 未放行)
// 时草稿会留着, 现场重试就会再建一张 —— 同一工单下堆出一串 PFI 废草稿。
// 口径: 同一工单 + DRAFT 状态的入库单只留一张, 重试时复用。
//
// 数量不一致时不复用: 入库单没有改单接口 (只有 add/check/delete/uncheck),
// 复用旧单会让实际入库量与现场填的数量对不上 —— 宁可新建一张, 让旧的走「删除」按钮。

export function findReusableDraft(records, orderId, expectedQty) {
  if (!Array.isArray(records) || !records.length) return null
  const oid = String(orderId == null ? '' : orderId)
  if (!oid) return null
  const drafts = records.filter((x) => x
    && String(x.prdOrderId) === oid
    && String(x.billStatus) === 'DRAFT')
  if (!drafts.length) return null
  if (expectedQty == null || expectedQty === '') return drafts[0]
  const want = Number(expectedQty)
  if (!Number.isFinite(want)) return drafts[0]
  const same = drafts.find((x) => Number(x.qty) === want)
  return same || null
}
