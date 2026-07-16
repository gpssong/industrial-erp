/**
 * 打印统一入口 (myprint-design)
 * - 缓存模板 (30 秒, 避免打印按钮重复请求后端)
 * - 构造 previewData, 把业务单据字段映射到模板 field
 * - 调用 MyPrinter.chromePrinter 浏览器打印
 */
import { MyPrinter } from 'myprint-design'
import { ElMessage } from 'element-plus'
import { printTemplateApi } from '@/api/system'

const cache = {} // { [bizType]: { ts, data|null } }

const CACHE_TTL = 30_000

export const BIZ_TYPES = Object.freeze({
  SAL_DELIVERY: 'SAL_DELIVERY',
  PUR_RECEIPT:  'PUR_RECEIPT',
  PUR_RETURN:   'PUR_RETURN',
  SAL_RETURN:   'SAL_RETURN',
  PRD_ORDER:    'PRD_ORDER'
})

export const BIZ_TYPE_LABEL = Object.freeze({
  SAL_DELIVERY: '销售出库单',
  PUR_RECEIPT:  '采购入库单',
  PUR_RETURN:   '采购退货单',
  SAL_RETURN:   '销售退货单',
  PRD_ORDER:    '生产加工单'
})

/**
 * 取指定 biz_type 当前生效模板, 30 秒内存缓存
 * @returns {Promise<Object|null>}
 */
export async function getTemplate(bizType) {
  const now = Date.now()
  const hit = cache[bizType]
  if (hit && now - hit.ts < CACHE_TTL) return hit.data
  try {
    const r = await printTemplateApi.getByBizType(bizType)
    const data = r && r.data ? r.data : null
    cache[bizType] = { ts: now, data }
    return data
  } catch (e) {
    cache[bizType] = { ts: now, data: null }
    return null
  }
}

/** 清除缓存 (模板保存/删除后调用) */
export function clearTemplateCache(bizType) {
  if (bizType) delete cache[bizType]
  else Object.keys(cache).forEach(k => delete cache[k])
}

/**
 * 把业务单据按 fieldMap 转成模板需要的 previewData.
 * - 所有"有值"的字段都转成 string. 这一步不是性格是防御: myprint-design 的 print 路径
 *   对 `previewDataTmp` 用 `if (!previewDataTmp)` 做空值判断 (0 在 JS 是 falsy), 会把
 *   BigDecimal 0 / 数字 0 当成空 → 走到 fallback 链 `formatter() → element.data`,
 *   对于 `field + 内容` 形态的 Text 元素会渲染空白.
 * - 明细行按 detailFieldMap 重命名.
 *
 * @param {Object} bill 后端返回的单据详情
 * @param {Object} fieldMap { templateField: billField, ... }
 * @param {string|null} detailsKey 单据明细数组在 bill 中的字段名 (如 'details'), 没有传 null
 * @param {Object|null} detailFieldMap { templateField: detailField, ... }
 * @returns {Object} previewData (所有值已 stringify, myprint 打印路径空值链会被绕过)
 */
export function buildPreviewData(bill, fieldMap, detailsKey, detailFieldMap) {
  const header = {}
  for (const [tplField, billField] of Object.entries(fieldMap || {})) {
    header[tplField] = toPrintValue(bill[billField])
  }
  if (!detailsKey || !Array.isArray(bill[detailsKey])) return header
  const dMap = detailFieldMap || {}
  header[detailsKey] = bill[detailsKey].map(row => {
    const out = {}
    for (const [tplField, srcField] of Object.entries(dMap)) {
      out[tplField] = toPrintValue(row[srcField])
    }
    return out
  })
  return header
}

/**
 * 把单值转成"打印字符串". 规则:
 * - null / undefined / '' → ''    (空字符串, 渲染层会跳过)
 * - 其它所有值 (含 0, false, BigDecimal 0) → String(val)    (绕过 myprint 的 `if (!x)` falsy 判断)
 */
function toPrintValue(v) {
  if (v == null || v === '') return ''
  return String(v)
}

/**
 * 把后端存的 panel JSON 字符串修正成 myprint v6 期望的形状. 实际只修两件事:
 * - 把历史上误存的 `type:'Barcode'|'QRCode'` 改成 `type:'Text', contentType:'Barcode'|'QrCode'`,
 *   让 print 路径 `else if (type == "Text")` 分支能进入;
 * - 把 `option.barcodeFormat` 等配置保留下来.
 * 幂等, 重复调用无副作用. 直接对 elementList 递归处理, 包含 tableHeadList/tableBodyList 内嵌字段.
 */
export function normalizePanel(panelJson) {
  let panel
  try { panel = typeof panelJson === 'string' ? JSON.parse(panelJson) : (panelJson || {}) }
  catch (e) { return panelJson }
  if (panel && Array.isArray(panel.elementList)) {
    for (const el of panel.elementList) normalizeElement(el)
  }
  if (panel && panel.pageHeader) normalizeElement(panel.pageHeader)
  if (panel && panel.pageFooter) normalizeElement(panel.pageFooter)
  return typeof panelJson === 'string' ? JSON.stringify(panel) : panel
}

function normalizeElement(el) {
  if (!el || typeof el !== 'object') return
  // 老存储: type='Barcode' or 'QRCode' (no contentType)
  if (el.type === 'Barcode' && !el.contentType) {
    el.type = 'Text'
    el.contentType = 'Barcode'
  } else if ((el.type === 'QRCode' || el.type === 'QrCode') && !el.contentType) {
    el.type = 'Text'
    el.contentType = 'QrCode'
  }
  // DataTable 内的列元素也可能直接标记为 Barcode / QrCode, 递归修
  for (const k of ['tableHeadList', 'tableBodyList', 'statisticsList', 'elementList']) {
    if (Array.isArray(el[k])) {
      for (const row of el[k]) {
        if (Array.isArray(row)) {
          for (const c of row) normalizeElement(c)
        } else if (row && typeof row === 'object') {
          normalizeElement(row)
        }
      }
    }
  }
}

/**
 * 触发浏览器打印
 *
 * @param {Object} opts
 * @param {string} opts.bizType         业务类型 (BIZ_TYPES.*)
 * @param {Object} opts.bill            单据详情对象
 * @param {Object} opts.fieldMap        主表字段映射
 * @param {string} [opts.detailsKey]    单据明细数组字段名, 无明细可省略
 * @param {Object} [opts.detailFieldMap] 明细行字段映射
 * @returns {Promise<void>}
 */
export async function doPrint({ bizType, bill, fieldMap, detailsKey, detailFieldMap }) {
  const tpl = await getTemplate(bizType)
  if (!tpl) {
    ElMessage.warning(`未配置 ${BIZ_TYPE_LABEL[bizType] || bizType} 的打印模板, 请联系管理员`)
    return
  }
  if (!tpl.content) {
    ElMessage.error('打印模板内容为空')
    return
  }
  // 修 #3: 老模板库里可能有 type:'Barcode'/'QRCode'(没 contentType)导致 print 路径被丢弃,
  // 这里做兜底归一化, 单次幂等, 不修改 tpl.content.
  const panel = normalizePanel(tpl.content)
  const data = buildPreviewData(bill, fieldMap, detailsKey, detailFieldMap)
  try {
    await MyPrinter.chromePrinter({
      panel,
      previewDataList: [data]
    })
  } catch (e) {
    console.error('[print] error', e)
    ElMessage.error('打印失败: ' + (e && e.message ? e.message : e))
  }
}

/**
 * 组合式 (兼容 setup 用法)
 */
export function usePrint() {
  return {
    getTemplate,
    clearTemplateCache,
    buildPreviewData,
    doPrint,
    BIZ_TYPES,
    BIZ_TYPE_LABEL
  }
}
