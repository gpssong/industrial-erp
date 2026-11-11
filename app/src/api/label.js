/**
 * v1.1.167 标签打印 App 侧接口（Task B4）。与 pc-web/src/api/base.js 的 labelApi 同端点、
 * 独立实现（uni-app 不能 import pc-web 的 axios）。
 *
 * `request` 已解包 R.data，失败会 reject。
 * 每个调用方各自 try/catch，一个失败不清空其它已取到的数据（v1.1.145 教训）。
 *
 * ⚠ **GET 的参数必须拼进 URL，不能塞 `data`**：`api/index.js` 里的 `fetchRequest()`
 *   （H5/浏览器分支）只给非 GET 请求带 body，GET 的 `data` 整份丢掉 —— 而原生
 *   `uni.request` 分支会把 `data` 拼成 query。也就是说同一个方法 **APK 里好使、
 *   H5 里静默丢参数** → 后端缺必填参数报 400，看着像「接口坏了」
 *   （`api/xk.js` 现有 GET 用 `data` 的写法就是隐患，只是它传的都是有默认值的分页参数才没暴露）。
 *   车间跑 APK，但开发期全走 H5 预览，不拼 URL 必踩 —— 与 api/index.js:195 的 `toQuery()` 同因。
 *
 * ⚠ **POST 一律带 `contentType: 'json'`**（全局约束「POST JSON 要 contentType: 'json'」）：
 *   `request()` 只在 contentType==='json' 时塞 Content-Type: application/json，
 *   漏了则原生分支走默认 urlencoded → Spring `@RequestBody` 报 500（v1.1.125 实测，
 *   见 api/index.js:244-247 注释）。这些端点后端全是 `@RequestBody`。
 */
import { request } from './index'

/** 只拼「有值」的键：undefined/null/'' 一律丢掉，避免 bizId= 之类把后端逼成 400。 */
function qs(o) {
  return Object.keys(o || {})
          .filter(k => o[k] !== undefined && o[k] !== null && o[k] !== '')
          .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(o[k])).join('&')
}

export const labelApi = {
  // 标签主数据（生产者名称/地址/XK 证号…）：后端门禁 base:label:list 或 base:product:list
  master: () => request({ url: '/base/label/master', method: 'GET' }),
  // 选单据：FG_ROLL / RAW_PALLET / CARTON / CERT / GOODS_LABEL 都能进 /bills。
  //   · CERT 后端只列「可打印」的 OQC 出厂检验单（CHECKED + PASS/PASS_DISPOSITION）
  //   · GOODS_LABEL 列 base_product 行（一行商品一张标）
  //   · 两者都没有明细行，/lines 对它们回空（后端 default -> {}）
  bills: (o) => request({ url: '/base/label/bills?' + qs(o), method: 'GET' }),
  // 选明细行：{id, name, qty, batchNo} —— **没有 productName**（R-B4-4，见 BaseLabelController.java:381-388）
  lines: (o) => request({ url: '/base/label/lines?' + qs(o), method: 'GET' }),
  // 该类型可用模板（门禁 base:label:template 或 base:label:list）
  templates: (bizType) => request({ url: '/base/label/template/list?' + qs({ bizType, status: 1 }), method: 'GET' }),
  preview: (body) => request({ url: '/base/label/preview', method: 'POST', data: body, contentType: 'json' }),
  tspl: (body) => request({ url: '/base/label/tspl', method: 'POST', data: body, contentType: 'json' }),
  // 打印日志回报：channel 由服务端钉成 BT_TSPL，前端不传。
  // silent=true 是硬要求：日志失败绝不能把已经出纸的打印报成失败（B4 评审 F1）。
  log: (body) => request({ url: '/base/label/print/log', method: 'POST', data: body, contentType: 'json', silent: true }),
  // 最近打印日志（排查「谁在手机上打错了什么」）
  logs: (o) => request({ url: '/base/label/logs?' + qs(o), method: 'GET' })
}

export default labelApi
