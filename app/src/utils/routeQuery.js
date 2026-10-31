// v1.1.140 — 深链参数怎么拿到才是对的
//
// 现象 (工单详情 ② 吹膜巡检 → 返回 → ④ 制袋巡检, 反之亦然):
//   URL 已是 #/pages/scan/pat?...stage=BAG&processId=<制袋>, 页面却渲染上一趟吹膜的内容
//   (标题「XK 生产 / 吹膜工序检验」、7 项吹膜模板、ensure-for-process 请求体也带吹膜的 processId)。
// 根因 (v1.1.140 在 .32 用 zzpat1140 + XHR/生命周期埋点实测):
//   本项目 APK 与 App H5 都跑 uni-app H5 运行时, utils/nav.js 靠改 location.hash 导航。
//   同一路由 (path 相同, 只有 query 不同) 再次进入时, uni-app 派发给目标页生命周期的时机
//   早于 URL 落地: 那一刻 location.hash 还是「上一页」的 (activeRouteQuery 按 path 比对不上,
//   只能返回 null), 而框架塞给 onLoad 的 query 又是「上一趟」的那份 —— 两条路都拿不到本次参数。
//   同时页面实例会被 keep-alive 复用, mounted/onLoad 不一定再跑, 所以只挂 hashchange 监听也不可靠。
// 修法 (按可信度排序, 页面里三档兜底):
//   1) takePendingRoute(PAGE_PATH) —— nav.js 在真正发起跳转「之前」把 path+query 记在模块里,
//      目标页 onLoad 取用; 不依赖 URL 时序, 也不依赖框架给的那份 query (首选)。
//   2) activeRouteQuery(PAGE_PATH) —— 冷启动直接打开深链 / 手动改地址栏时 URL 已经是本次的。
//   3) onLoad 的 query 参数 —— 最后的兜底。
//   另外保留 onShow + hashchange/popstate 订阅, 覆盖「URL 落地后参数和已生效值不一致」的场景。
//   hash 里没有页面路径时, activeRoute() 退回 getCurrentPages() 的页面栈参数; 都拿不到返回 null。

function decode(s) {
  try {
    return decodeURIComponent(String(s).replace(/\+/g, ' '))
  } catch (e) {
    return String(s)
  }
}

function parseQuery(search) {
  const query = {}
  String(search || '').split('&').forEach(pair => {
    if (!pair) return
    const eq = pair.indexOf('=')
    const key = decode(eq < 0 ? pair : pair.slice(0, eq))
    if (!key) return
    if (!(key in query)) query[key] = eq < 0 ? '' : decode(pair.slice(eq + 1))
  })
  return query
}

/** 当前激活路由的 path (去掉 query, 以 / 开头) + query; 解析不出来返回 null */
export function activeRoute() {
  if (typeof window !== 'undefined' && window.location) {
    const hash = String(window.location.hash || '')
    const fromHash = hash.startsWith('#') ? hash.slice(1) : hash
    const source = fromHash.indexOf('/') === 0
      ? fromHash
      : String(window.location.pathname || '') + (window.location.search || '')
    if (source.indexOf('/pages/') === 0) {
      const qi = source.indexOf('?')
      return {
        path: qi < 0 ? source : source.slice(0, qi),
        query: parseQuery(qi < 0 ? '' : source.slice(qi + 1))
      }
    }
  }
  // 非 hash 路由 (或 URL 尚未更新) 时退回 uni-app 页面栈
  try {
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    const cur = pages[pages.length - 1]
    if (!cur) return null
    const route = String(cur.route || cur.__route__ || '')
    if (!route) return null
    const info = cur.$page || {}
    return { path: '/' + route, query: info.options || info.query || cur.options || {} }
  } catch (e) {
    return null
  }
}

/** 该 path 是否就是指定页面 (pages.json 里的 /pages/xxx/yyy) */
export function isRouteActive(pagePath) {
  const route = activeRoute()
  return !!route && route.path === pagePath
}

/** 当前 URL 上属于指定页面的参数; 页面不是这个 path 或解析失败时返回 null */
export function activeRouteQuery(pagePath) {
  const route = activeRoute()
  if (!route || (pagePath && route.path !== pagePath)) return null
  return route.query
}

const ROUTE_EVENTS = ['hashchange', 'popstate']

// 发起跳转前登记的「这一趟要去哪、带什么参数」(见文件头 修法 1)
const PENDING_TTL_MS = 5000
let pendingRoute = null

/** nav.js / uni.navigateTo 调用点在实际跳转前登记目标路由。url 形如 /pages/scan/pat?orderId=1&stage=BAG */
export function rememberRoute(url) {
  const text = String(url || '').replace(/^#/, '')
  if (text.indexOf('/pages/') !== 0) return
  const qi = text.indexOf('?')
  pendingRoute = {
    path: qi < 0 ? text : text.slice(0, qi),
    query: parseQuery(qi < 0 ? '' : text.slice(qi + 1)),
    at: Date.now()
  }
}

/** 目标页 onLoad 取走本次跳转登记的参数 (取一次就失效, 不影响下一趟);
 *  不是给本页登记的、或登记已超过 PENDING_TTL_MS (跳转被打断) 时返回 null。 */
export function takePendingRoute(pagePath) {
  if (!pendingRoute || pendingRoute.path !== pagePath) return null
  if (Date.now() - pendingRoute.at > PENDING_TTL_MS) {
    pendingRoute = null
    return null
  }
  const query = pendingRoute.query
  pendingRoute = null
  return query
}

/** 订阅「本页面 URL 上的参数变了」。页面 mounted 时调用, 返回取消订阅函数 (beforeUnmount 调用)。
 *  只有当前路由就是 pagePath 时才回调; 跳到别的页面时静默, 不影响本页面实例。
 *  没有 window 的环境 (纯原生 App 端) 返回空函数, 由 onShow 兜底。
 *  这是 URL 落地后的纠偏通道 —— 首屏那一刻 URL 可能还没更新 (见文件头), 所以它不能替代
 *  onLoad 里的 takePendingRoute/activeRouteQuery, 两者是互补的。 */
export function onActiveRouteChange(pagePath, handler) {
  if (typeof window === 'undefined' || typeof window.addEventListener !== 'function') return () => {}
  const run = () => {
    const query = activeRouteQuery(pagePath)
    if (query) handler(query)
  }
  ROUTE_EVENTS.forEach((name) => window.addEventListener(name, run, false))
  return () => ROUTE_EVENTS.forEach((name) => window.removeEventListener(name, run, false))
}

// v1.1.140 实测 (CDP 直接读组件实例): 同路由再次进入时, uni-app 的 keep-alive 会把子组件那份 props
//   「保住」—— 页面数据换了 (processId=BAG), 但重建出来的子组件实例上
//     vnode.props = { 'process-id': 新值, processId: 旧值, stage: 旧值, ... }
//   模板里写的 kebab 属性拿到新值, 组件真正声明使用的 camel 属性 (processId / stage / orderId) 仍是旧值,
//   所以子组件继续按上一道工序 (吹膜) 渲染并发请求。传参不能只靠 props —— 页面把本次参数登记到这里,
//   子组件自己读 + 订阅, 与 Vue 的 props 合成无关。
let inspectionContext = null
const inspectionSubscribers = new Set()

/** 页面每次解析出「这一趟要检哪个工单/工序/阶段」时登记 (同 inspectionType 的组件据此工作) */
export function setInspectionContext(context) {
  inspectionContext = { ...(context || {}) }
  inspectionSubscribers.forEach((fn) => {
    try { fn(inspectionContext) } catch (e) { /* 订阅者出错不能影响页面 */ }
  })
}

/** 组件 mounted 时取当前上下文 (首屏 props 可能已是这次的, 也可能被保住, 以这里为准) */
export function getInspectionContext() {
  return inspectionContext
}

/** 订阅上下文变化, 返回取消订阅函数 (beforeUnmount 调用) */
export function onInspectionContextChange(handler) {
  inspectionSubscribers.add(handler)
  return () => inspectionSubscribers.delete(handler)
}
