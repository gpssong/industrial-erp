/**
 * 浏览器下载封装 —— 之前每个页面各写一遍
 * 「建 a 标签 → 设 href → 设 download → click → revokeObjectURL」,
 * 漏掉 revoke 会在长会话里攒住 blob 内存。
 */

/** 触发浏览器下载一个 Blob, 返回实际使用的文件名。 */
export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
  return filename
}

/** 建一个带 BOM 的 CSV Blob —— Excel 打开中文不乱码。 */
export function csvBlob(text) {
  return new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8' })
}

/** 建一个带 BOM 的 TSV Blob。 */
export function tsvBlob(text) {
  return new Blob(['﻿' + text], { type: 'text/tab-separated-values;charset=utf-8' })
}

/** 从 content-disposition 响应头取文件名, 取不到用 fallback。 */
export function filenameFromDisposition(header, fallback) {
  const m = /filename\*=UTF-8''([^;]+)|filename="([^"]+)"/i.exec(header || '')
  return m ? decodeURIComponent(m[1] || m[2]) : fallback
}
