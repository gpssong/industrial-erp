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
