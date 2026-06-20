import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import router from '@/router'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'

NProgress.configure({ showSpinner: false })

const service = axios.create({
  // 开发模式(Vite proxy)用 /api；Electron 打包后从 localStorage 读取配置的服务器地址
  baseURL: import.meta.env.DEV ? '/api' : (localStorage.getItem('erp_api_base') || '/api'),
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
  ElMessage.error(err.message || '网络异常')
  return Promise.reject(err)
})

export default service
