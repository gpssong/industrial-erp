/**
 * v1.1.167 标签打印机蓝牙直发 (docs/32 §7.1 / §9 / Task B4)。
 *
 * 铁律：指令字节**一律来自后端 POST /base/label/tspl**。App 侧零指令拼装 ——
 * 否则以后换厂商（CPCL/ZPL）要改两处，「唯一不浪费的投入」这条就破了。
 * 绑定关系只存手机本地（一台手机一台机，不进库：车间换机不该去找 PC 管理员）。
 *
 * ⚠ 插件句柄的取法（Task B4 裁定 R-B4-1，别再退回旧写法）：
 *   必须 `registerPlugin('LabelBle')`（docs/34 的取证与生产路径用的是同一句取法）。
 *   `window.Capacitor.Plugins.LabelBle` 在本项目实测落到 Capacitor 的**桩**上，
 *   调用即抛 "LabelBle.print() is not implemented on android" —— 看着像插件没注册，
 *   其实是取句柄方式错了（docs/34 取证就是靠 registerPlugin 跑通的）。
 *   代价：registerPlugin 在 H5 里也返回一个「方法都存在」的代理，所以**不能**再拿
 *   「对象在不在」当原生判据 —— 判原生只认 utils/runtime.js 的 isNativeApp()。
 *
 * ⚠ 权限（Task B4 裁定 R-B4-2）：插件的 scan()/connect()/print() 三个方法进门就
 *   `ensureBlePermission(...)`，没授权直接 reject「缺少蓝牙扫描权限…请点「申请蓝牙权限」」。
 *   本项目此前**没有任何运行时授权流程**（AndroidManifest 只是声明），所以生产页必须
 *   自己先把权限要到手：`ensurePermissions()` 是唯一入口，扫描前调一次。
 */
import { isNativeApp } from './runtime.js'
import { registerPlugin } from '@capacitor/core'

/** 绑定关系存储键：{ mac, name }，只存手机本地。 */
const KEY = 'erp_label_printer'

const LabelBle = registerPlugin('LabelBle')

/**
 * 桥面/接口异常归一 (Task B4 裁定 R-B4-7)：Capacitor 的 reject 给的是 `e.message`，
 * `request()` 的 reject 给的是后端 `R` 里的 `e.msg`，`uni.*` 失败给 `e.errMsg`，
 * 而 `disconnect()` 之类在无插件时抛的是**裸字符串**。页面只写一个 helper 就够 ——
 * 漏掉裸字符串时 `String(e)` 兜住，不再出现「[object Object]」这种工人读不出来的提示。
 */
export function errText(e) {
  if (!e) return '未知错误'
  if (typeof e === 'string') return e
  return e.message || e.msg || e.errMsg || String(e)
}

export function getBinding() {
  try { return uni.getStorageSync(KEY) || null } catch (e) { return null }
}

export function setBinding(mac, name) {
  if (!mac) { try { uni.removeStorageSync(KEY) } catch (e) {} ; return }
  uni.setStorageSync(KEY, { mac, name: name || '' })
}

/** 桥面是否可用：只有 Capacitor 原生容器里才有真插件（H5/浏览器里没有蓝牙权限模型）。 */
export function nativeReady() {
  return isNativeApp()
}

function plugin() {
  if (!nativeReady()) return null
  return LabelBle
}

const NO_PLUGIN_MSG = '当前环境没有蓝牙插件（请用车间 Android APK，H5 里不能蓝牙直发）'

/**
 * 申请蓝牙运行时权限（Android 12+ 的「附近的设备」BLUETOOTH_SCAN/BLUETOOTH_CONNECT，
 * 11 及以下的 ACCESS_FINE_LOCATION）。
 * 成功 resolve `{ granted, apiLevel, alias }`；用户拒绝 → reject（消息已由插件写好，
 * 含「请在系统设置 → 应用 → 权限里允许」），调用方把这段文案原样呈现即可。
 */
export async function ensurePermissions() {
  const p = plugin()
  if (!p) throw new Error(NO_PLUGIN_MSG)
  const r = await p.ensurePermissions()
  return r || { granted: true }
}

/**
 * 扫描周边设备。返回 `{ mac, name, rssi }` 列表；未授权的桥面异常原样抛给调用方，
 * 由页面决定怎么告诉工人（生产页在 openBind() 里先调 ensurePermissions()）。
 */
export async function scan(timeoutMs = 6000) {
  const p = plugin()
  if (!p) throw new Error(NO_PLUGIN_MSG)
  const r = await p.scan({ timeoutMs })
  return (r && r.devices) || []
}

export async function connect(mac) {
  const p = plugin()
  if (!p) throw new Error(NO_PLUGIN_MSG)
  return p.connect({ mac })
}

export async function status() {
  const p = plugin()
  if (!p) return { connected: false, mac: null, name: null }
  return p.status()
}

export async function disconnect() {
  const p = plugin()
  if (!p) return {}
  return p.disconnect()
}

/**
 * 直接发后端给的 base64 原始字节（编码已在服务端按取证结论落定为 GBK，这里绝不再编码一次）。
 * 分片参数默认取自 docs/34 的实测值：**片间隔 30ms，片长不在这里算** ——
 * 插件里按 `min(MAX_WRITE_CHUNK=180, 有效MTU-3)` 一处算（docs/34 明确「B4 的分片默认值 = 180，
 * 不是 MTU−3」：协商 MTU 247 也被 180 这个常量截住）。要压片长才显式传 `mtu`。
 * 返回体原样透传：`{ sentBytes, chunks, missingAcks, chunkSize, mtu, elapsedMs }`，
 * `missingAcks > 0` 就是「分片打一半」的前兆，页面要报出来（docs/34 §分片）。
 */
export async function send(base64Content, opts = {}) {
  const p = plugin()
  if (!p) throw new Error(NO_PLUGIN_MSG)
  if (!base64Content) throw new Error('没有要发送的指令内容')
  const payload = {
    contentBase64: base64Content,
    chunkDelayMs: opts.chunkDelayMs ?? 30
  }
  // 只在调用方显式给了 mtu 时才下发（否则用插件协商到的值，docs/34 实测能谈满 247）
  if (opts.mtu) payload.mtu = opts.mtu
  const r = await p.print(payload)
  return r || { sentBytes: 0, chunks: 0, missingAcks: 0 }
}
