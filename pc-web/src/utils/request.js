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

console.log('[request] baseURL =', apiBase, '| localStorage erp_api_base =', localStorage.getItem('erp_api_base'))

const service = axios.create({
  baseURL: apiBase,
  timeout: 30000
})

// 请求拦截
service.interceptors.request.use(config => {
  NProgress.start()
  const user = useUserStore()
  if (user.token) config.headers['Authorization'] = user.token  // Sa-Token 直接取值，不加 Bearer 前缀
  // 调试: 打印每次请求的完整 URL (首次 404 时排查)
  console.log('[request] ->', (config.baseURL || '') + config.url)
  return config
}, err => Promise.reject(err))

// 401 防重入锁: 同一时刻只弹一个确认框, 避免 token 过期时多个并发请求叠加弹窗
let isShowing401 = false
function handle401(msg) {
  if (isShowing401) return
  isShowing401 = true
  ElMessageBox.confirm(msg || '登录已过期, 请重新登录', '提示', { type: 'warning' })
    .then(() => {
      router.push('/login')
    })
    .catch(() => {})
    .finally(() => { isShowing401 = false })
}

// 响应拦截
service.interceptors.response.use(res => {
  NProgress.done()
  const data = res.data
  if (data.code === 200) return data
  if (data.code === 401) {
    handle401(data.msg)
    return Promise.reject(new Error(data.msg || '未登录'))
  }
  ElMessage.error(data.msg || '操作失败')
  return Promise.reject(new Error(data.msg))
}, err => {
  NProgress.done()
  // HTTP 层 401 (例如 Nginx 反代未鉴权)
  if (err.response && err.response.status === 401) {
    handle401()
    return Promise.reject(err)
  }
  ElMessage.error(err.message?.includes('Network') ? '无法连接服务器, 请在登录页底部「服务器连接设置」中检查 API 地址' : (err.message || '操作失败'))
  return Promise.reject(err)
})

export default service
