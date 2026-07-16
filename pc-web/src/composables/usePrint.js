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
 * 把业务单据按 fieldMap 转成模板需要的 previewData
 * - header 字段直接拷贝
 * - 明细行按 detailFieldMap 重命名
 *
 * @param {Object} bill 后端返回的单据详情
 * @param {Object} fieldMap { templateField: billField, ... }
 * @param {string|null} detailsKey 单据明细数组在 bill 中的字段名 (如 'details'), 没有传 null
 * @param {Object|null} detailFieldMap { templateField: detailField, ... }
 * @returns {Object} previewData
 */
export function buildPreviewData(bill, fieldMap, detailsKey, detailFieldMap) {
  const header = {}
  for (const [tplField, billField] of Object.entries(fieldMap || {})) {
    header[tplField] = bill[billField] != null ? bill[billField] : ''
  }
  if (!detailsKey || !Array.isArray(bill[detailsKey])) return header
  const dMap = detailFieldMap || {}
  header[detailsKey] = bill[detailsKey].map(row => {
    const out = {}
    for (const [tplField, srcField] of Object.entries(dMap)) {
      out[tplField] = row[srcField] != null ? row[srcField] : ''
    }
    return out
  })
  return header
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
  const data = buildPreviewData(bill, fieldMap, detailsKey, detailFieldMap)
  try {
    await MyPrinter.chromePrinter({
      panel: tpl.content,
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