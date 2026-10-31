import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import router from '@/router'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'

NProgress.configure({ showSpinner: false })

// v1.1.79 hotfix-2: 雪花 ID (> 2^53) 在 JSON.stringify 时丢精度。
// 现象: AI 助手提议 addPurIn 时 supplierId=2073700724874723329 会被 JS 读成 Number → 2073700724874723300 (丢 29 位),
//       后端 selectById(2073700724874723300) 找不到 → 报"供应商不存在"。
// 修复: request 拦截器递归把 > MAX_SAFE_INTEGER 的数字序列化为字符串, Jackson 默认支持 String→Long 反序列化。
// 边界: 业务字段 (qty/price/税率 等) 全是小数字, 不会被字符串化。
const SAFE_MAX = Number.MAX_SAFE_INTEGER
// v1.1.130 hotfix: 只有 JSON 载荷才需要遍历改写 — FormData/Blob/File/ArrayBuffer/URLSearchParams
// 一旦被当普通对象遍历, Object.keys(FormData) 为空, 请求体会被替换成 {} →
// 后端 `Required part 'file' is not present` (上传全站失效)。
const isJsonPayload = (v) => Array.isArray(v) || Object.prototype.toString.call(v) === '[object Object]'
function stringifyBigInts(value) {
  if (Array.isArray(value)) return value.map(stringifyBigInts)
  if (value && typeof value === 'object') {
    const out = {}
    for (const k of Object.keys(value)) {
      const v = value[k]
      if (typeof v === 'number' && !Number.isFinite(v)) {
        // NaN/Infinity 兜底转字符串 (避免 axios 序列化为 null 静默丢精度)
        out[k] = v === null ? null : String(v)
      } else if (typeof v === 'number' && Math.abs(v) > SAFE_MAX) {
        // 大整数 (雪花 ID 19 位, 远超 2^53) → 字符串保精度
        out[k] = String(v)
      } else {
        out[k] = stringifyBigInts(v)
      }
    }
    return out
  }
  return value
}

// 优先级: Electron 注入的完整 API URL > localStorage(用户手动配置) > 环境变量(VITE_API_BASE) > 默认 /api
// 在 Electron 中, window.__ERP_API_BASE__ 由 preload.js 注入, 包含完整的远端 API 地址
// 这样可以避免 file:// 协议下 axios 请求 /api 时变成 file:///api/xxx
const _electronApiBase = typeof window !== 'undefined' && window.__ERP_API_BASE__
const _resolvedBase = _electronApiBase
  || localStorage.getItem('erp_api_base')
  || import.meta.env.VITE_API_BASE
  || '/api'

// 校验: 必须以 /api 开头, 否则拦截并提示, 避免静默报 Network Error
const isValidApiBase = (base) => typeof base === 'string' && (base.startsWith('http') || base.startsWith('/api'))
const apiBase = isValidApiBase(_resolvedBase) ? _resolvedBase : '/api'
if (_resolvedBase !== apiBase) {
  console.warn('[request] 无效的 API 地址 "%s", 已自动恢复为默认 /api', _resolvedBase)
  localStorage.removeItem('erp_api_base')
}

// v1.1.145: 给「不走 axios 的下载」用 (CSV 导出要 fetch + credentials, axios 拦截器只认 code===200 的 JSON)。
// 导出的是同一个判定结果, 别在页面里再拼一遍 Electron/localStorage 优先级。
export const API_BASE = apiBase

const service = axios.create({
  baseURL: apiBase,
  timeout: 30000,
  // P1-2: 允许跨域请求带 Cookie (httpOnly SameSite=Lax)
  // 同源部署 (反代 /api) 时此参数无影响; 用户在登录页配置远程 API 时才真正需要
  withCredentials: true
})

// 请求拦截
service.interceptors.request.use(config => {
  NProgress.start()
  // v1.1.79 hotfix-2: 大整数 → 字符串 (避免雪花 ID 精度丢失)
  if (config.data && typeof config.data === 'object' && isJsonPayload(config.data)) {
    config.data = stringifyBigInts(config.data)
  }
  // 不再显式带 Authorization header, 由浏览器自动附带 httpOnly Cookie (Sa-Token cookie)
  // 若需要兼容老会话 (用户在旧的 header 模式登录), 仍带 header 但不影响 cookie 模式登录
  const user = useUserStore()
  if (user.token) config.headers['Authorization'] = user.token
  return config
}, err => Promise.reject(err))

// 401 防重入锁: 同一时刻只弹一个确认框, 避免 token 过期时多个并发请求叠加弹窗
let isShowing401 = false
function handle401(msg) {
  const cur = router.currentRoute.value
  // 未登录页面 (如 /login) 上的 401 不是"登录过期": 不提示, 也绝不写入 redirect=/login
  // —— 否则登录成功后会被 redirect 拉回登录页, 表现为"登录成功但不跳转"。
  if (cur.meta?.public) return
  if (isShowing401) return
  isShowing401 = true
  ElMessageBox.confirm(msg || '登录已过期, 请重新登录', '提示', { type: 'warning' })
    .then(() => {
      router.push({ path: '/login', query: { redirect: cur.fullPath } })
    })
    .catch(() => {})
    .finally(() => { isShowing401 = false })
}

// 响应拦截
service.interceptors.response.use(res => {
  NProgress.done()
  const data = res.data
  // 防御: 后端返回 HTML/字符串(例如 nginx 502/404 页面)时, data 不是对象, 不能 .code
  if (typeof data === 'object' && data !== null && data.code === 200) return data
  if (typeof data === 'object' && data !== null && data.code === 401) {
    // silent401: 明确预期可能未登录的请求 (登录页读品牌配置等), 不弹框不改路由
    if (!res.config?.silent401) handle401(data.msg)
    return Promise.reject(new Error(data.msg || '未登录'))
  }
  // v1.1.31: 不在拦截器弹 ElMessage, 把 data.msg 一并附给 Error 对象,
  // 让组件的 catch 统一弹窗 (避免双弹 + 防止拦截器弹的 msg 被组件 catch 覆盖导致用户看不到)
  const bizErr = new Error((data && data.msg) || '服务器响应格式异常')
  bizErr.msg = (data && data.msg) || ''
  bizErr.code = (data && data.code) || -1
  return Promise.reject(bizErr)
}, err => {
  NProgress.done()
  // HTTP 层 401 (例如 Nginx 反代未鉴权)
  if (err.response && err.response.status === 401) {
    if (!err.config?.silent401) handle401()
    return Promise.reject(err)
  }
  // P1-5: 错误日志脱敏 — 生产环境仅记错误码/HTTP/请求路径, 不打请求体/userInfo/cookie
  if (err.response) {
    if (import.meta.env.DEV) {
      const url = (err.config?.baseURL || '') + (err.config?.url || '')
      const code = err.response.data?.code
      const msg = err.response.data?.msg || err.response.data?.message
      console.error('[HTTP_ERR]', err.response.status, url, '| code:', code, '| msg:', msg)
    }
    // 生产环境只记录最小信息到 Sentry/后端日志, 不暴露请求细节
  }
  // v1.1.11+: 401 (未登录) / 403 (无权限) 静默 — 让路由守卫和上游组件各自处理, 不弹红条
  if (err.response && (err.response.status === 401 || err.response.status === 403)) {
    return Promise.reject(err)
  }
  ElMessage.error(
    err.code === 'ERR_NETWORK' ? '无法连接服务器, 请在登录页底部「服务器连接设置」中检查 API 地址'
    : err.code === 'ECONNABORTED' ? '请求超时, 请重试'
    : (err.message || '操作失败')
  )
  return Promise.reject(err)
})

export default service
