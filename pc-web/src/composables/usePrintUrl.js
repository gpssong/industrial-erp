/**
 * 打印 URL 构建工具
 * 在 Electron file:// 协议下, 相对路径 /api/print/... 会解析失败,
 * 所以需要基于当前页面的 origin 拼接完整 URL.
 */
export function getPrintUrl(path, id) {
  const origin = window.location.origin // 远端 http://xxx 或 file:// 下的 origin
  const token = localStorage.getItem('erp_token') || ''
  // 如果当前页面是 file://, 需要替换 origin 为远端地址
  if (origin === 'file://') {
    const apiBase = window.__ERP_API_BASE__ || 'http://home.93gushi.com:8088/api'
    const webBase = apiBase.replace('/api', '')
    return `${webBase}${path}/${id}.html?token=${token}&_t=${Date.now()}`
  }
  return `${origin}${path}/${id}.html?token=${token}&_t=${Date.now()}`
}
