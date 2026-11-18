/**
 * v1.1.196 — 工序检验 (首检 FAI / 巡检 PAT) 的签核口径, 与服务端
 * QmsInspectionService.validateProcessResult 同源:
 *
 *   定量项 (unit 非空, 如厚度 mm / 宽度 mm / 拉伸强度 MPa) —— 必须填实测值;
 *   定性项 (unit 为空, 如原料核对 / 膜面外观 / 异味 / 清洁与异物控制) —— 明确判定即可。
 *
 * 现场依据: 进货检验 (IQC) 一直是「只点判定不填文本」(已签核 IQC 单的定性项 result_value 全为空),
 * 首检/巡检此前比它严一档, 导致车间「全点合格」签不下来。
 */

/** 是否定量项 (需要实测值)。 */
export function isMeasuredItem(item) {
  return !!(item && String(item.unit == null ? '' : item.unit).trim())
}

/** 判定值归一: 未判定 → NaN。 */
function flagOf(item) {
  const raw = item ? item.passFlag : null
  return (raw === null || raw === undefined || raw === '') ? NaN : Number(raw)
}

/**
 * v1.1.200 — 该检验项是否还没完成 (未判定 / 定量项缺实测值)。
 * 页面用它给必填项打 * 标识: 未完成的项显示 *, 判完/填完就消失。
 * 与 missingInspectionItems 同源, 两边必须一致。
 */
export function isItemIncomplete(item) {
  const flag = flagOf(item)
  if (![0, 1, 2].includes(flag)) return true
  if (flag === 2) return false
  return isMeasuredItem(item) && !String((item && item.resultValue) || '').trim()
}

/** 未完成记录/判定的检验项: [{ item, index, reason }]。 */
export function missingInspectionItems(items) {
  const missing = []
  ;(Array.isArray(items) ? items : []).forEach((item, index) => {
    const flag = flagOf(item)
    if (![0, 1, 2].includes(flag)) {
      missing.push({ item, index, reason: '未判定' })
      return
    }
    if (flag === 2) return
    if (isMeasuredItem(item) && !String((item && item.resultValue) || '').trim()) {
      missing.push({ item, index, reason: '缺实测值' })
    }
  })
  return missing
}

/** 现场提示语: 点名缺哪几项, 避免只说「有未完成项」让人猜。 */
export function describeMissingInspectionItems(items) {
  const missing = missingInspectionItems(items)
  if (!missing.length) return ''
  const parts = missing.map(entry => {
    const name = (entry.item && entry.item.itemName) || `第${entry.index + 1}项`
    return `${name}（${entry.reason}）`
  })
  return `以下检验项未完成：${parts.join('、')}。定性项点判定即可，定量项（厚度/宽度等）须填实测值。`
}
