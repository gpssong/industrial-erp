// v1.1.105 — 运行时探测单一权威源
//
// 背景 (App 登录失败根因): 本文件创建前, 「是不是 H5」这个判断被复制到 5 个文件
//   (api/index.js / utils/nav.js / utils/upload.js / pages/login/index.vue / pages/profile/settings.vue),
//   5 处全是同一句:
//       typeof window !== 'undefined' && typeof document !== 'undefined' && typeof plus === 'undefined'
//   这句只认 HBuilder 原生运行时的全局对象 `plus`, 但本项目 APK 是 **Capacitor 6** 打包的,
//   Capacitor WebView 里根本没有 `plus` → 5 处全部把 APK 误判成 H5。
//
// 后果链 (v1.1.105 实测 logcat):
//   isH5Runtime() 误判 true → getBase() 返回相对路径 '/api'
//   → 请求打到 Capacitor WebView 自己的 http://localhost/api/...
//   → Capacitor 本地服务器找不到该文件, SPA fallback 返回打包进 APK 的 index.html (text/html)
//   → "[api] 非 JSON 响应: status=200 ct=text/html" → 登录接口同样拿不到 JSON → 无法登录。
//
// 判定顺序 (先认 Capacitor, 再退回 HBuilder 的 plus, 最后才是浏览器):
//   1. window.Capacitor 存在 → 看 isNativePlatform(); true=原生, false=浏览器里跑的 Capacitor web 版
//   2. 无 Capacitor 但有 plus          → HBuilder 原生运行时 (真机 App)
//   3. 两者都无                          → 浏览器 = H5
//
// 另注: App H5 (部署在 :18090 的那份 dist) 在浏览器里打开时 window.Capacitor 不存在,
//       走分支 3 判为 H5, 与既有行为一致, 零破坏。

/** 是否 Capacitor 原生容器 (APK) —— 与 isH5Runtime() 严格互补 */
export function isNativeApp() {
  if (typeof window === 'undefined') return false
  const cap = window.Capacitor
  if (cap) {
    // Capacitor 6 注入的全局对象: { getPlatform, isNativePlatform, isNative }
    try {
      if (typeof cap.isNativePlatform === 'function') return !!cap.isNativePlatform()
      if (typeof cap.getPlatform === 'function') return cap.getPlatform() !== 'web'
    } catch (e) {
      // Capacitor API 尚未就绪时退回按 platform 字符串判断
      try {
        if (typeof cap.getPlatform === 'function') return cap.getPlatform() !== 'web'
      } catch (e2) {}
    }
    return true // 有 Capacitor 全局但方法不可用 → 保守当原生 (宁可走绝对地址, 也不要静默打 localhost)
  }
  // HBuilder 原生运行时 (未走 Capacitor 打包的老路径)
  return typeof plus !== 'undefined'
}

/** 是否 H5 浏览器 (含 App H5 :18090) */
export function isH5Runtime() {
  return typeof window !== 'undefined' && typeof document !== 'undefined' && !isNativeApp()
}