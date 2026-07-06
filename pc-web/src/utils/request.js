import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import router from '@/router'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'

NProgress.configure({ showSpinner: false })

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

const service = axios.create({
  baseURL: apiBase,
  timeout: 30000
})

// 请求拦截
service.interceptors.request.use(config => {
  NProgress.start()
  const user = useUserStore()
  if (user.token) config.headers['Authorization'] = user.token  // Sa-Token 直接取值，不加 Bearer 前缀
  return config
}, err => Promise.reject(err))

// 401 防重入锁: 同一时刻只弹一个确认框, 避免 token 过期时多个并发请求叠加弹窗
let isShowing401 = false
function handle401(msg) {
  if (isShowing401) return
  isShowing401 = true
  ElMessageBox.confirm(msg || '登录已过期, 请重新登录', '提示', { type: 'warning' })
    .then(() => {
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
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
    handle401(data.msg)
    return Promise.reject(new Error(data.msg || '未登录'))
  }
  ElMessage.error((data && data.msg) || '服务器响应格式异常')
  return Promise.reject(new Error((data && data.msg) || '服务器响应格式异常'))
}, err => {
  NProgress.done()
  // HTTP 层 401 (例如 Nginx 反代未鉴权)
  if (err.response && err.response.status === 401) {
    handle401()
    return Promise.reject(err)
  }
  // HTTP 层 403/500 等: 把完整 URL / status / 后端 R.msg 都打到 console,
  // 便于排查 "Failed to load resource" 类的纯浏览器原生错误 (例如反代层 403)
  if (err.response) {
    const url = (err.config?.baseURL || '') + (err.config?.url || '')
    const code = err.response.data?.code
    const msg = err.response.data?.msg || err.response.data?.message
    console.error('[HTTP_ERR]', err.response.status, url, '| code:', code, '| msg:', msg)
  }
  ElMessage.error(
    err.code === 'ERR_NETWORK' ? '无法连接服务器, 请在登录页底部「服务器连接设置」中检查 API 地址'
    : err.code === 'ECONNABORTED' ? '请求超时, 请重试'
    : (err.message || '操作失败')
  )
  return Promise.reject(err)
})

export default service
