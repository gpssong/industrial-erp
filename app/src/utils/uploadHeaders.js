// App 上传请求头 — 原生 multipart 修复 (2026-11-08)
//
// 根因 (2026-11-08 真机取证: Mi 10 Ultra / Android 13 / 生产 APK):
//   照片走 pickAndUploadImage → uploadFile → uni.uploadFile, 底层是 XHR。APK 里
//   CapacitorHttp.enabled=true 接管 XHR, FormData 不显式带 Content-Type 时, 原生发出的
//   请求到后端被判为非 multipart → 回 `Current request is not a multipart request` (code 500)
//   → 页面 "上传失败"。同一探针: 显式带 `multipart/form-data; boundary=<token>` → code 200。
//   原生 writeFormDataRequestBody 从 Content-Type 里 split(";")[1].split("=")[1] 取 boundary,
//   所以 token 内不能含 ; = 空格。文档上传走 fetch (CapacitorHttp 对 fetch 自动补 Content-Type),
//   带不带都 200; 为口径一致原生两条路径都带。
//
// 真浏览器 (H5) 相反: 必须由浏览器按实际序列化自动生成 boundary, 手动设会与实际不符,
//   所以只在原生容器加 Content-Type。isNative 由调用方传 utils/runtime.js 的 isNativeApp()。

/**
 * 构造上传请求头。
 * @param {boolean} isNative  是否 Capacitor/HBuilder 原生容器
 * @param {object} [extra]    额外请求头 (合并, 不覆盖 X-Client-Type)
 * @returns {object} headers
 */
export function buildUploadHeaders(isNative, extra) {
  const headers = Object.assign({ 'X-Client-Type': 'APP' }, extra || {})
  if (isNative) {
    // base36 时间戳 + 随机段: 只含 0-9a-z, 配 ---- 前缀, 不含 ; = 空格, 原生 split 安全。
    const boundary = '----CapacitorUpload' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
    headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
  }
  return headers
}