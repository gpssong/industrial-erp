// 统一导航: 原生 App 走 uni API, H5 走 hash 路由
export function isNative() {
  return typeof plus !== 'undefined'
}

const TABBAR_PAGES = [
  '/pages/dashboard/index',
  '/pages/inventory/query',
  '/pages/sales/quick',
  '/pages/report/index',
  '/pages/profile/index'
]

export function navigateTo(url) {
  if (isNative()) {
    if (TABBAR_PAGES.includes(url)) {
      uni.switchTab({ url })
    } else {
      uni.reLaunch({ url })
    }
  } else {
    window.location.hash = '#' + url
  }
}
