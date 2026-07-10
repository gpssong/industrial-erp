// 扫码工具: 优先用本地原生 ZXing Activity (v1.1.8+ 解决遮挡 + 超时)
// 降级: @capacitor-community/barcode-scanner / prompt
// 实现:
//   1. NativeScanner (本项目自定义) - 直接 launch CaptureActivity 全屏扫码, 100% 不被 WebView 遮挡
//   2. BarcodeScanner (@capacitor-community) - 有 WebView 叠加 bug, 兜底
//   3. prompt - 最低降级
import { registerPlugin } from '@capacitor/core'
import { BarcodeScanner } from '@capacitor-community/barcode-scanner'

// 注册本地原生插件 (对应 com.pengcheng.erp.NativeScannerPlugin)
const NativeScanner = registerPlugin('NativeScanner')

export function isH5() {
  return typeof window !== 'undefined' && typeof document !== 'undefined'
}

export function isCapacitor() {
  return typeof Capacitor !== 'undefined' && Capacitor.isNativePlatform && Capacitor.isNativePlatform()
}

export function uniScanCodeAvailable() {
  if (isH5() || isCapacitor()) return false
  return typeof uni !== 'undefined' && typeof uni.scanCode === 'function'
}

// 强制关闭摄像头 (页面切换/退出时调用, 仅对 BarcodeScanner 插件有效)
export async function stopScan() {
  if (isCapacitor()) {
    try { await BarcodeScanner.stopScan() } catch (e) {}
    try { await BarcodeScanner.showBackground() } catch (e) {}
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
      console.log('[scan] calling NativeScanner.startScan()')
      const result = await NativeScanner.startScan()
      console.log('[scan] NativeScanner result:', JSON.stringify(result))
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
if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', () => { stopScan() })
  window.addEventListener('beforeunload', () => { stopScan() })
  if (typeof uni !== 'undefined') {
    uni.on && uni.on('onHide', () => { stopScan() })
  }
}
