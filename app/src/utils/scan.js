// 扫码工具: uni-app 原生扫码 / Capacitor BarcodeScanner / prompt 降级
import { BarcodeScanner } from '@capacitor-community/barcode-scanner'

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

// 通用扫码入口
// opts: { onResult(text), onCancel(), onError(err) }
export async function doScan(opts) {
  // uni-app 原生扫码 (HBuilderX 打包)
  if (uniScanCodeAvailable()) {
    return new Promise((resolve) => {
      uni.scanCode({
        success: (res) => { opts.onResult && opts.onResult(res.result); resolve(true) },
        fail: (err) => { opts.onCancel ? opts.onCancel() : (opts.onError && opts.onError(err)); resolve(false) }
      })
    })
  }

  // Capacitor: 原生扫码
  if (isCapacitor()) {
    try {
      // 检查权限
      const status = await BarcodeScanner.checkPermission({ force: true })
      if (!status.granted) {
        // 请求权限
        const reqStatus = await BarcodeScanner.checkPermission({ force: true })
        if (!reqStatus.granted) {
          alert('需要相机权限才能扫码，请在设置中授权')
          opts.onCancel && opts.onCancel()
          return
        }
      }

      // 隐藏 WebView 背景 (扫码需要透明)
      document.querySelector('body').style.background = 'transparent'
      document.querySelector('#app').style.background = 'transparent'

      // 开始扫码
      BarcodeScanner.hideBackground()
      const result = await BarcodeScanner.startScan()

      // 恢复背景
      document.querySelector('body').style.background = ''
      document.querySelector('#app').style.background = ''

      if (result.hasContent) {
        opts.onResult && opts.onResult(result.content)
      } else {
        opts.onCancel && opts.onCancel()
      }
    } catch (e) {
      // 恢复背景
      document.querySelector('body').style.background = ''
      document.querySelector('#app').style.background = ''

      // 用户取消或其他错误
      if (e.message && (e.message.includes('cancel') || e.message.includes('User'))) {
        opts.onCancel && opts.onCancel()
      } else {
        // 降级到 prompt
        const c = prompt('扫码失败，请手动输入条码/商品编码:')
        if (c && c.trim()) {
          opts.onResult && opts.onResult(c.trim())
        } else {
          opts.onCancel && opts.onCancel()
        }
      }
    }
    return
  }

  // H5 浏览器: prompt 输入
  const c = (typeof window !== 'undefined' ? window.prompt : prompt)('请输入条码 / 商品编码')
  if (c && c.trim()) {
    opts.onResult && opts.onResult(c.trim())
  } else {
    opts.onCancel && opts.onCancel()
  }
}
