import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './styles/index.scss'
import './styles/responsive.css'

// ResizeObserver 警告静默
// Element Plus 的 el-tabs/el-table/el-dialog 频繁重排时, ResizeObserver 通知可能
// 在前一帧 callback 执行前就堆积, 触发 "ResizeObserver loop completed with undelivered notifications".
// 这是浏览器原生 warning, 无害, 但污染 console. 在这里过滤掉.
const _origError = window.console.error
window.console.error = (...args) => {
  const msg = args[0]
  if (typeof msg === 'string' && msg.includes('ResizeObserver loop')) return
  _origError.apply(window.console, args)
}
// 也过滤 window.onerror 的抛错 (某些浏览器会作为 error 事件上报)
window.addEventListener('error', (e) => {
  if (e && e.message && e.message.includes('ResizeObserver loop')) {
    e.stopImmediatePropagation()
    e.preventDefault()
  }
}, true)

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
for (const [k, v] of Object.entries(ElementPlusIconsVue)) app.component(k, v)
app.mount('#app')
