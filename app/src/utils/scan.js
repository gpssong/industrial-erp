// 扫码兼容工具: 统一处理 uni-app 扫码逻辑
// 关键: H5 环境下 uni 对象存在, 但 uni.scanCode 是空函数, 必须主动判断
export function isH5() {
  return typeof window !== 'undefined' && typeof document !== 'undefined'
}

// H5 环境下, uni.scanCode 是空函数, 需要走 html5-qrcode
export function uniScanCodeAvailable() {
  if (isH5()) return false
  return typeof uni !== 'undefined' && typeof uni.scanCode === 'function'
}

// 通用扫码入口: H5 走 html5-qrcode, 其他环境走 uni.scanCode
// opts: { onResult(text), onCancel(), onError(err), onClose() }
export async function doScan(opts) {
  if (uniScanCodeAvailable()) {
    return new Promise((resolve) => {
      uni.scanCode({
        success: (res) => { opts.onResult && opts.onResult(res.result); resolve(true) },
        fail: (err) => { opts.onCancel ? opts.onCancel() : (opts.onError && opts.onError(err)); resolve(false) }
      })
    })
  }
  // H5: 优先用 html5-qrcode (需要摄像头), 失败时降级为 prompt
  try {
    if (typeof Html5Qrcode !== 'undefined' || (await import('html5-qrcode'))) {
      // 真实扫码界面 (依赖调用方实现 UI 弹层)
      opts.onH5 && opts.onH5()
      return
    }
  } catch (e) { /* fallthrough */ }
  // 降级: prompt 输入
  const c = (typeof window !== 'undefined' ? window.prompt : prompt)('请输入条码 / 商品编码')
  if (c && c.trim()) {
    opts.onResult && opts.onResult(c.trim())
  } else {
    opts.onCancel && opts.onCancel()
  }
}
