// 服务器地址单一权威源
//
// 背景: 服务器设置原先在 5 处各写一份 localStorage 读写逻辑, 且都不校验/补全 /api。
//   用户输入 `47.96.188.129` / `http://47.96.188.129` 时, 请求会打到 host 根路径,
//   被 nginx 的 SPA fallback 返回 index.html → 前端收到 HTML, 看起来就是"设置无效"。
//
// 约定:
//   - 未写协议 → 自动补 http:// (企业内网/自建服务器常见的 HTTP 场景)
//   - 未写 /api → 自动补 /api (本项目后端 context-path 固定为 /api)
//   - 空值 → 返回 '', 表示恢复默认 (H5 同源 /api; 原生默认服务器)

const STORAGE_KEY = 'erp_api_base'

export function normalizeApiBase(raw) {
  let s = String(raw == null ? '' : raw).trim()
  if (!s) return ''
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s
  s = s.replace(/\/+$/, '')
  if (!/\/api$/i.test(s)) s += '/api'
  return s
}

export function getApiBaseOverride() {
  try {
    return normalizeApiBase(localStorage.getItem(STORAGE_KEY))
  } catch (e) {
    return ''
  }
}

export function setApiBaseOverride(raw) {
  const v = normalizeApiBase(raw)
  try {
    if (v) localStorage.setItem(STORAGE_KEY, v)
    else localStorage.removeItem(STORAGE_KEY)
  } catch (e) {}
  return v
}
