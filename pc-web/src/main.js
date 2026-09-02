import { createApp } from 'vue'
import { createPinia } from 'pinia'
// v1.1.24+: Element Plus 改按需引入 (由 unplugin-vue-components 自动扫描 <el-*> 标签 +
// unplugin-auto-import 自动注入 ElMessage/ElNotification 等 API).
// 之前全量 import + app.use(ElementPlus) 会让整个 element-plus (~700KB) 打进主 chunk,
// 即使未访问的组件也一起下载. 现在删掉全量引入, 体积直接减 60%+.
// 注意: 如果有用 <el-*>-style 指令/插件仍需保留全量 css, 但我们只用组件, 故可删.
// v1.1.24+: myprint-design 改异步动态加载 — 仅在用户进入"打印模板设计器"路由时才下载 1.6MB 的
// printer chunk + 字体. 之前在 main.js 静态 import 导致 printer 进入首屏强制预加载.
// 注意: 'myprint-design/css/index.css' 保留 (设计器专用 CSS, ~30KB, 只在路由懒加载时跟着走).
// 业务打印 (用 usePrint.js 调 chromePrinter) 不依赖 myprint-design, 走 window.print() 原生.
// Element Plus icons 按需收集 — 只注册路由 + 业务页面实际用到的 (~30 个),
// 既支持 <Search /> 组件式也支持 <component :is="'Search'"> 字符串式 (侧边栏菜单).
// 全量注册 200+ icons ~50KB gzip, 收集后约 5KB; 完全值得.
import { Search, Plus, Refresh, Delete, Edit, Download, Upload, View, Lock, User, UserFilled, Menu, OfficeBuilding, Setting, Printer, Goods, Avatar, Connection, House, DataLine, List, Box, Back, Tickets, TakeawayBox, Notebook, Document, Files, SetUp, Money, TrendCharts, PieChart, Close, Check, ArrowLeft, ArrowRight, Warning, InfoFilled, CircleCheck, CircleClose, Loading, Sort, Filter, Calendar, Bell, Tools, Promotion, MoreFilled, Star, StarFilled, Flag, ChatLineRound, DataAnalysis, ChatDotRound, Position, Phone, Message } from '@element-plus/icons-vue'
// v1.1.31: Element Plus 按需组件 (unplugin-vue-components 自动 <el-*>) 不覆盖 ElMessageBox.confirm/alert 这种 API 调用
// 必须手动 import message-box / message / notification 样式, 否则弹窗无背景色
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-notification.css'
import App from './App.vue'
import router from './router'
import './styles/index.scss'
import './styles/responsive.css'

// ResizeObserver 警告静默
const _origError = window.console.error
window.console.error = (...args) => {
  const msg = args[0]
  if (typeof msg === 'string' && msg.includes('ResizeObserver loop')) return
  _origError.apply(window.console, args)
}
window.addEventListener('error', (e) => {
  if (e && e.message && e.message.includes('ResizeObserver loop')) {
    e.stopImmediatePropagation()
    e.preventDefault()
  }
}, true)

const app = createApp(App)
app.use(createPinia())
app.use(router)
// 按需注册 icons
;[Search, Plus, Refresh, Delete, Edit, Download, Upload, View, Lock, User, UserFilled, Menu, OfficeBuilding, Setting, Printer, Goods, Avatar, Connection, House, DataLine, List, Box, Back, Tickets, TakeawayBox, Notebook, Document, Files, SetUp, Money, TrendCharts, PieChart, Close, Check, ArrowLeft, ArrowRight, Warning, InfoFilled, CircleCheck, CircleClose, Loading, Sort, Filter, Calendar, Bell, Tools, Promotion, MoreFilled, Star, StarFilled, Flag, ChatLineRound, DataAnalysis, ChatDotRound, Position, Phone, Message].forEach(c => app.component(c.name, c))

// myprint-design 异步初始化: 立即创建应用, 用户访问"打印模板"路由时按需加载.
// MyPrinter 必须在第一个打印调用前初始化, createPrint 是 Vue 插件. 用动态 import +
// 路由守卫实现"按路由懒加载".
async function lazyInitMyprint() {
  try {
    const mod = await import('myprint-design')
    mod.MyPrinter.initMyPrinter({ disabledClient: true })
    app.use(mod.createPrint)
    // 加载设计器专用 CSS (设计器路由组件内已 import, 这里再保险一次)
    await import('myprint-design/css/index.css')
  } catch (e) {
    console.warn('myprint-design 异步加载失败, 打印模板设计器不可用:', e.message)
  }
}
// 不阻塞首屏, fire-and-forget
lazyInitMyprint()

app.mount('#app')
