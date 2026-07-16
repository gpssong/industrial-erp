import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// myprint-design 打印模板设计器样式
import 'myprint-design/css/index.css'
import { MyPrinter, createPrint } from 'myprint-design'
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
// 初始化 myprint 客户端配置
// chromePrinter (浏览器打印) 不依赖任何外部服务
// disabledClient: true 阻止 INIT_SOCKET 反复连接 ws://127.0.0.1:9898 (桌面客户端未安装时会刷 ERR_CONNECTION_REFUSED)
MyPrinter.initMyPrinter({ disabledClient: true })
app.use(createPrint)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
for (const [k, v] of Object.entries(ElementPlusIconsVue)) app.component(k, v)
app.mount('#app')
