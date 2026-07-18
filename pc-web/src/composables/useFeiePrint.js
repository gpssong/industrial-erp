/**
 * 飞鹅云打印 Composable
 * 用于生产单等业务的飞鹅云打印预览和打印
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { feiePrintApi } from '@/api/feie'
import request from '@/utils/request'

/** 打印机配置列表 */
export const printerConfigs = ref([])

/** 加载打印机配置 */
export async function loadPrinterConfigs() {
  try {
    const r = await feiePrintApi.listPrinters()
    printerConfigs.value = (r.data || [])
  } catch (e) {
    console.warn('[feie] 加载打印机配置失败', e)
    printerConfigs.value = []
  }
}

/**
 * 预览飞鹅打印 (返回 HTML 字符串)
 * @param {number} orderId
 * @returns {Promise<string|null>}
 */
export async function previewFeiePrint(orderId) {
  try {
    const r = await feiePrintApi.preview(orderId)
    return r.data || null
  } catch (e) {
    ElMessage.error('预览失败: ' + (e.message || '未知错误'))
    return null
  }
}

/**
 * 发送到飞鹅云打印
 * @param {number} orderId
 * @param {number|null} configId - 指定打印机配置 ID, 为空则用默认
 * @returns {Promise<boolean>}
 */
export async function sendFeiePrint(orderId, configId = null) {
  try {
    let url = `/feie/print/prd-order/${orderId}`
    if (configId) {
      url = `/feie/print/prd-order/${orderId}/config/${configId}`
    }
    const r = await request.post(url)
    ElMessage.success('打印成功')
    return true
  } catch (e) {
    ElMessage.error('打印失败: ' + (e.message || '未知错误'))
    return false
  }
}
