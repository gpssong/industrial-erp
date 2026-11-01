// v1.1.155: App 端打开已上传附件 (校准证书 / 校准报告).
//   - 图片: uni.previewImage (可缩放, 左右滑)
//   - APK: NativeShare.openFile → 下载到 filesDir + FileProvider ACTION_VIEW (系统 PDF/Office 阅读器)
//   - H5:  window.open (浏览器自带 PDF 预览 / 下载)
// 上传接口返回的是绝对 URL (SysUploadController.absoluteBase), 三种方式都能直接用。
import { registerPlugin } from '@capacitor/core'
import { isNativeApp } from './runtime.js'

const NativeShare = registerPlugin('NativeShare')

const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp']
const MIME_BY_EXT = {
  pdf: 'application/pdf',
  doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xls: 'application/vnd.ms-excel',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
}

function extOf(url) {
  const clean = String(url || '').split('?')[0].toLowerCase()
  const dot = clean.lastIndexOf('.')
  return dot >= 0 ? clean.substring(dot + 1) : ''
}

export function isImageUrl(url) {
  return IMAGE_EXTS.includes(extOf(url))
}

export function fileNameOf(url) {
  const clean = String(url || '').split('?')[0]
  const name = clean.substring(clean.lastIndexOf('/') + 1)
  return name || '附件'
}

/**
 * 打开附件. 调用方负责 toast 错误 (失败时 reject).
 * @param {string} url  绝对 URL
 * @param {string} [name] 展示名 (file 名缺失时用)
 */
export async function openAttachment(url, name) {
  if (!url) throw new Error('附件地址为空')
  if (isImageUrl(url)) {
    await new Promise((resolve) => {
      uni.previewImage({
        urls: [url],
        current: url,
        success: () => resolve(true),
        fail: () => resolve(false)
      })
    })
    return
  }
  const fileName = name || fileNameOf(url)
  const ext = extOf(url)
  const mimeType = MIME_BY_EXT[ext] || 'application/octet-stream'
  if (isNativeApp()) {
    await NativeShare.openFile({ url, fileName, mimeType })
    return
  }
  if (typeof window !== 'undefined') {
    window.open(url, '_blank')
    return
  }
  throw new Error('当前环境不支持打开附件')
}
