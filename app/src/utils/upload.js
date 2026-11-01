// v1.1.101 XK — App 端上传工具
// 复用 pc-web api/workflow.js:uploadFile() 模式, 走 /api/system/upload/file (10MB 上限)
// 后端直接写盘, 不入 sys_upload 表, 返 url 即可
import { request } from '../api/index.js'

// 上传基础地址: 与 request() 一致 — 读 storage.erp_api_base 或 H5 同源 '/api', 原生用绝对地址
// v1.1.105: 原判断内联且只认 `plus`, 会把 Capacitor APK 判成 H5 → 返回 '' 让上传打到 WebView 本地。
//           改引 utils/runtime.js 的 isH5Runtime (单一权威源)。
import { isH5Runtime } from './runtime.js'
import { getApiBaseOverride } from './apiBase.js'

// 原生/Capacitor: 与 api/index.js 的 DEFAULT_API 保持一致
const NATIVE_UPLOAD_BASE = 'http://home.93gushi.com:8088/api'

function getUploadBase() {
  // 用户显式覆盖优先 (与 api/index.js 共用同一份归一化逻辑)
  const override = getApiBaseOverride()
  if (override) return override
  // H5 同源: 相对路径 '/api' —— 后端 context-path 固定是 /api, 留空会被 nginx 当静态资源
  // (v1.1.132 浏览器实测: 空 base → POST /system/upload/file → nginx 405 + index.html → JSON.parse 炸)
  if (isH5Runtime()) return '/api'
  // 原生/Capacitor 默认地址
  return NATIVE_UPLOAD_BASE
}

/**
 * 上传本地文件 (uni.chooseImage 拿到的 tempFilePath) 到后端, 返回 url.
 * @param {string} filePath  本地文件路径
 * @param {object} [opts]    { name, header }
 * @returns {Promise<{url, filename, size, contentType}>}
 */
export function uploadFile(filePath, opts = {}) {
  return new Promise((resolve, reject) => {
    if (typeof uni === 'undefined' || typeof uni.uploadFile !== 'function') {
      reject(new Error('uni.uploadFile 不可用, 仅原生/Capacitor 环境支持'))
      return
    }
    const base = getUploadBase()
    uni.uploadFile({
      url: base + '/system/upload/file',
      filePath,
      name: opts.name || 'file',
      header: Object.assign({ 'X-Client-Type': 'APP' }, opts.header || {}),
      success: (r) => {
        try {
          const j = typeof r.data === 'string' ? JSON.parse(r.data) : r.data
          if (j && (j.code === 200 || j.code === 0)) {
            const data = j.data || {}
            resolve({
              url: data.url,
              filename: data.filename || data.url,
              size: data.size || 0,
              contentType: data.contentType || ''
            })
          } else {
            reject(new Error((j && j.msg) || '上传失败'))
          }
        } catch (e) {
          reject(e)
        }
      },
      fail: (e) => reject(e instanceof Error ? e : new Error(JSON.stringify(e)))
    })
  })
}

/**
 * v1.1.155: 上传浏览器 File 对象 (校准证书 / 报告 PDF).
 *
 * 为何不用 uni.uploadFile: 它只认 filePath (原生磁盘路径 / blob URL), 而文档选择拿到的是 File;
 * 且 blob URL 丢失原文件名 → 后端按扩展名白名单校验会拒。
 * fetch + FormData 保留真实文件名: H5 同源直传; APK 里 CapacitorHttp 已接管 fetch (与 uni.uploadFile 同一层),
 * 失败再退回 uni.uploadFile (blob URL) 兜底。
 */
export async function uploadFileObject(file) {
  if (!file) throw new Error('未选择文件')
  if (file.size && file.size > 10 * 1024 * 1024) throw new Error('文件超过 10MB 上限')
  const base = getUploadBase()
  const fd = new FormData()
  fd.append('file', file, file.name || ('upload-' + Date.now()))
  try {
    const res = await fetch(base + '/system/upload/file', {
      method: 'POST',
      body: fd,
      credentials: 'include',
      headers: { 'X-Client-Type': 'APP' }
    })
    const j = await res.json().catch(() => null)
    if (j && (j.code === 200 || j.code === 0)) {
      const d = j.data || {}
      return {
        url: d.url,
        filename: d.filename || file.name,
        size: d.size || file.size || 0,
        contentType: d.contentType || file.type || ''
      }
    }
    throw new Error((j && j.msg) || '上传失败')
  } catch (e) {
    // fetch 层失败 (原生 WebView 不支持 FormData 直传时) → 退回 uni.uploadFile
    if (typeof e === 'object' && e && e.message && e.message.indexOf('上传失败') < 0 && typeof URL !== 'undefined') {
      const objUrl = URL.createObjectURL(file)
      try { return await uploadFile(objUrl, { name: 'file' }) } finally {
        try { URL.revokeObjectURL(objUrl) } catch (e2) {}
      }
    }
    throw e
  }
}

/**
 * v1.1.155: 选文档 (PDF / 图片 / Office) 并上传 — 校准证书、校准报告.
 * 用隐藏 <input type=file>: H5 与 Capacitor WebView 都原生支持文件选择;
 * uni.chooseFile 在 App 端不存在, 所以不能走它。
 * @param {string} [accept] accept 属性, 默认证书/报告常见格式
 */
export function pickAndUploadDocument(accept = '.pdf,image/*,.doc,.docx,.xls,.xlsx') {
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined') {
      reject(new Error('当前环境不支持选择文档'))
      return
    }
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = accept
    input.style.display = 'none'
    document.body.appendChild(input)
    let settled = false
    const cleanup = () => {
      settled = true
      try { window.removeEventListener('focus', onFocus) } catch (e) { /* ignore */ }
      try { document.body.removeChild(input) } catch (e) { /* ignore */ }
    }
    // 取消选择时浏览器只触发 window focus 不触发 change, 不兜底会让调用方的 loading 永远不消失
    const onFocus = () => {
      setTimeout(() => {
        if (settled) return
        if (!input.files || !input.files.length) {
          cleanup()
          reject(new Error('取消选择'))
        }
      }, 400)
    }
    window.addEventListener('focus', onFocus)
    input.addEventListener('change', async () => {
      const file = input.files && input.files[0]
      if (settled) return
      cleanup()
      if (!file) { reject(new Error('未选择文件')); return }
      try { resolve(await uploadFileObject(file)) } catch (e) { reject(e) }
    })
    input.click()
  })
}

/**
 * 一站式选图 + 上传 (扫码入库附件/照片用).
 * @param {object} [opts] chooseImage 选项, 默认 count=1 sourceType=['camera','album']
 * @returns {Promise<{url, filename, size, contentType}>}
 */
export async function pickAndUploadImage(opts = {}) {
  const ci = await new Promise((resolve, reject) => {
    if (typeof uni === 'undefined' || typeof uni.chooseImage !== 'function') {
      reject(new Error('uni.chooseImage 不可用'))
      return
    }
    uni.chooseImage({
      count: opts.count || 1,
      sourceType: opts.sourceType || ['camera', 'album'],
      success: (r) => resolve(r),
      fail: reject
    })
  })
  const path = ci.tempFilePaths && ci.tempFilePaths[0]
  if (!path) throw new Error('未选择文件')
  return await uploadFile(path)
}
