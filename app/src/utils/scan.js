// 扫码工具: 优先用本地原生 ZXing Activity (v1.1.8+ 解决遮挡 + 超时)
// 降级: @capacitor-community/barcode-scanner / prompt
// 实现:
//   1. NativeScanner (本项目自定义) - 直接 launch CaptureActivity 全屏扫码, 100% 不被 WebView 遮挡
//   2. BarcodeScanner (@capacitor-community) - 有 WebView 叠加 bug, 兜底
//   3. prompt - 最低降级
//
// 注意: Capacitor 和 barcode-scanner 依赖只在 H5 以外环境才加载,
//       通过动态 import()/registerPlugin 实现 tree-shaking,
//       避免 H5 产物白浪费 ~140KB。
//
// v1.1.106: 运行时判定收敛到 utils/runtime.js (R19 单一权威源)。
//   原判断是本文件私有的第二套:
//     isH5()       = window && document 存在        → APK 里恒为 true (WebView 当然有)
//     isCapacitor()= typeof Capacitor !== 'undefined' → 查**裸全局**而非 window.Capacitor
//   前者会把 APK 误判成 H5, 后者靠 @capacitor/core 恰好注入全局才碰巧能用 (非契约)。
//   与 v1.1.105 登录失败的根因同源, 现在统一走 runtime.js。
import { registerPlugin } from '@capacitor/core'
import { isNativeApp, isH5Runtime } from './runtime.js'

const NativeScanner = registerPlugin('NativeScanner')

let BarcodeScanner = null // lazy-loaded below

// 保留导出名兼容既有调用方, 语义改为 runtime.js 的统一判定
export function isH5() {
  return isH5Runtime()
}

export function isCapacitor() {
  return isNativeApp()
}

// 懒加载 @capacitor-community/barcode-scanner
async function getBarcodeScanner() {
  if (!BarcodeScanner) {
    const mod = await import('@capacitor-community/barcode-scanner')
    BarcodeScanner = mod.BarcodeScanner
  }
  return BarcodeScanner
}

export function uniScanCodeAvailable() {
  if (isH5() || isCapacitor()) return false
  return typeof uni !== 'undefined' && typeof uni.scanCode === 'function'
}

// 强制关闭摄像头 (页面切换/退出时调用, 仅对 BarcodeScanner 插件有效)
export async function stopScan() {
  scanStarted = false
  if (isCapacitor()) {
    try {
      const BS = await getBarcodeScanner()
      if (BS) { await BS.stopScan(); await BS.showBackground() }
    } catch (e) {}
  }
}

// 通用扫码入口
// opts: { onResult(text), onCancel(), onError(err) }
export async function doScan(opts) {
  // uni-app 原生扫码 (HBuilderX 打包, 非 Capacitor)
  if (uniScanCodeAvailable()) {
    return new Promise((resolve) => {
      uni.scanCode({
        success: (res) => { opts.onResult && opts.onResult(res.result); resolve(true) },
        fail: (err) => { opts.onCancel ? opts.onCancel() : (opts.onError && opts.onError(err)); resolve(false) }
      })
    })
  }

  // Capacitor: 优先用本地原生 ZXing (v1.1.8+)
  if (isCapacitor()) {
    try {
      scanStarted = true
      const result = await NativeScanner.startScan()
      if (result && result.hasContent && result.content) {
        opts.onResult && opts.onResult(result.content)
      } else {
        opts.onCancel && opts.onCancel()
      }
      return
    } catch (e) {
      console.error('[scan] NativeScanner error:', e)
      // NativeScanner 不存在 (老版本) 或出错, 降级到 prompt
      const msg = (e && e.message) || String(e || '')
      if (msg.toLowerCase().includes('not implemented') || msg.toLowerCase().includes('not found')) {
        // 真的没有这个插件, 走 prompt
        return promptFallback(opts)
      }
      // 其它错误 (用户取消等)
      if (msg.toLowerCase().includes('cancel') || msg.toLowerCase().includes('user')) {
        opts.onCancel && opts.onCancel()
        return
      }
      // 其它异常 (例如权限被拒), 降级 prompt
      return promptFallback(opts, '扫码启动失败: ' + msg)
    }
  }

  // H5 浏览器: prompt 输入
  return promptFallback(opts)
}

function promptFallback(opts, prefixMsg) {
  const placeholder = prefixMsg
    ? prefixMsg + '\n请手动输入条码 / 商品编码:'
    : '请输入条码 / 商品编码'
  const c = (typeof window !== 'undefined' ? window.prompt : prompt)(placeholder)
  if (c && c.trim()) {
    opts.onResult && opts.onResult(c.trim())
  } else {
    if (prefixMsg) opts.onError && opts.onError(new Error(prefixMsg))
    else opts.onCancel && opts.onCancel()
  }
}

// 监听页面切换, 自动关闭摄像头 (仅 BarcodeScanner 插件需要)
//
// v1.1.106: 原来这段在**模块顶层**无条件执行, 后果:
//   1. 被 6 个 scan 页面各自 import → 同一批 pagehide/beforeunload 监听器重复注册 N 遍
//   2. 无任何清理路径, 卸载页面后监听器仍在
//   3. 从没启动过扫码也会调 stopScan() → 触发 @capacitor-community/barcode-scanner 的
//      动态 import + stopScan(), 该插件对未启动的扫码会抛 "not implemented"
// 现在: 改为幂等的 installScanLifecycle(), 由 App.vue onLaunch 显式调一次;
// 并用 started 标志跳过"没扫过就别关"的空调用。
let scanStarted = false
let lifecycleInstalled = false

export function installScanLifecycle() {
  if (lifecycleInstalled) return
  if (typeof window === 'undefined') return
  lifecycleInstalled = true

  const closeIfStarted = () => {
    if (!scanStarted) return
    scanStarted = false
    stopScan()
  }
  window.addEventListener('pagehide', closeIfStarted)
  window.addEventListener('beforeunload', closeIfStarted)
  if (typeof uni !== 'undefined' && uni.on) {
    uni.on('onHide', closeIfStarted)
  }
}
