<template>
  <view class="page">
    <view class="card">
      <view class="row">
        <text class="lab">标签类型</text>
        <picker :range="bizTypes" range-key="label" :value="bizIdx" @change="onBiz">
          <view class="val">{{ bizTypes[bizIdx].label }}</view>
        </picker>
      </view>
      <view class="row">
        <text class="lab">单据</text>
        <picker :range="bills" range-key="label" :value="billIdx" @change="onBill">
          <view class="val">{{ bills.length ? bills[billIdx].label : '加载中/无单据' }}</view>
        </picker>
      </view>
      <view v-if="lines.length" class="row col">
        <text class="lab">明细行（不勾 = 整单一张）</text>
        <view v-for="(l, i) in lines" :key="l.id" class="lrow" @click="toggleLine(i)">
          <text class="cbox">{{ picked.includes(i) ? '☑' : '☐' }}</text>
          <text class="ltext">{{ l.label }}</text>
        </view>
      </view>
      <view class="row">
        <text class="lab">模板</text>
        <picker :range="templates" range-key="label" :value="tplIdx" @change="onTpl">
          <view class="val">{{ templates.length ? templates[tplIdx].label : '无可用模板' }}</view>
        </picker>
      </view>
      <view v-if="!bills.length && loadedOnce" class="warn">这个类型下没取到单据：可能是本类型确实没单，也可能该账号没有对应单据权限。</view>
    </view>

    <view class="card">
      <view class="row">
        <text class="lab">打印机</text>
        <text class="val">{{ binding ? (binding.name || binding.mac) : '未绑定' }}</text>
      </view>
      <view class="row">
        <text class="lab">连接</text>
        <text class="val">{{ connText }}</text>
      </view>
      <view class="btns">
        <button size="mini" @click="openBind">绑定/换机</button>
        <button size="mini" :disabled="!native || !binding" @click="reconnect">重连</button>
        <button size="mini" :disabled="!native" @click="doDisconnect">断开</button>
      </view>
      <view v-if="!native" class="warn">当前不是 App 原生环境，H5 里不能蓝牙直发：请用车间 Android APK，或到 PC「标签打印中心」导出 TSPL / 走飞鹅。</view>
      <view v-else-if="!printPerm" class="warn">这个账号没有「标签打印」权限（base:label:print）：只能选单和看预览，打印按钮是灰的。要找 PC 端「角色管理」把标签打印授给它。</view>
    </view>

    <view v-if="preview.length" class="card">
      <text class="lab">屏显预览（只是版式，中文字体/条码实际效果取决于打印机字库，docs/32 §6.3）</text>
      <view v-for="(r, ri) in preview" :key="ri" class="canvas" :style="canvasStyle(r)">
        <view class="cap">第 {{ ri + 1 }} 张 · {{ r.widthDots }}×{{ r.heightDots }} 点 · 份数 {{ r.copies || 1 }}</view>
        <view v-for="(el, ei) in (r.elements || [])" :key="ei"
              class="el" :class="{ miss: el.missing }"
              :style="styleOf(el, r)">
          <text>{{ showText(el) }}</text>
        </view>
      </view>
      <text v-if="missing.length" class="missnote">必填项取值为空：{{ missing.join('、') }}（不阻断打印）</text>
    </view>

    <view class="btns big">
      <button class="primary" :loading="busy" :disabled="!canPrint" @click="doPrint">打印（每段 {{ tplCopies }} 张 × {{ preview.length || 1 }} 段）</button>
      <button :disabled="busy" @click="loadPreview">预览</button>
    </view>

    <view v-if="lastError" class="card">
      <text class="missnote">{{ lastError }}</text>
    </view>

    <view v-if="lastTspl" class="card">
      <text class="lab">最近一次指令原文（TSPL 文本，可整段复制去 PC「标签打印中心」导出同一张，docs/32 §5）</text>
      <textarea class="tspl" :value="lastTspl" auto-height disabled />
    </view>

    <view v-if="bindPop" class="pop" @click="bindPop = false">
      <view class="popbody" @click.stop>
        <text class="lab">扫描到的打印机（点一下即绑定）</text>
        <view v-if="!devices.length" class="val">{{ scanHint }}</view>
        <scroll-view scroll-y class="devlist">
          <view v-for="d in devices" :key="d.mac" class="dev" @click="pick(d)">
            <text>{{ d.name }}</text><text class="mac">{{ d.mac }}</text>
          </view>
        </scroll-view>
        <button size="mini" @click="bindPop = false">关闭</button>
      </view>
    </view>
  </view>
</template>

<script>
/**
 * v1.1.167 标签打印中心（Task B4）—— 车间手机蓝牙直发服务端渲染好的 TSPL。
 *
 * 四条口径（docs/32 §7，实现时别"顺手优化"掉）：
 *  ① 指令字节来自 POST /base/label/tspl，App 不拼指令（换厂商只改后端渲染器）；
 *  ② base64 原样交给插件，任何一端都不许再 TextEncoder/getBytes("UTF-8") 二次编码
 *     （服务端已按 docs/34 取证结论落定 GBK，B2 的成果会当场作废）；
 *  ③ 失败也要 reportLog（status=FAIL + errorMsg），并把 TSPL 文本回显给用户复制去 PC；
 *  ④ 预览只是版式预览，文案已写明中文实际效果取决于打印机字库。
 *
 * 一次发送 = 一份完整指令包：后端把每条标签各带自己的 `PRINT n,1` 拼成一个 blob
 * （contentsBase64 只有 1 个元素，copies 是各段份数之和），所以本页面**绝不再乘一遍份数**。
 */
import { labelApi } from '@/api/label'
import {
  getBinding, setBinding, scan, connect, disconnect, status, send,
  nativeReady, ensurePermissions, errText
} from '@/utils/labelPrinter'
import { guardPage, isPermEnabled } from '@/utils/appModuleEnabled'
import { checkPagePermission, isAdmin, getAppPermissions } from '@/utils/permission'

const BIZ = [
  { value: 'FG_ROLL', label: '成品卷标' },
  { value: 'RAW_PALLET', label: '原料垛标' },
  { value: 'CARTON', label: '箱标' }
  // CERT 合格证不做入口：/bills 对它显式 400（A9），列出来就是个点了必报错的死按钮（与 A12 打印中心一致）
]

const VIEW_W = 320   // 预览可视宽：640 点 → 320px（与 PC 的 LabelCenter 同一口径）

/** MAC 比对前的归一：插件回的是系统地址，绑定值来自同一次扫描，大小写/空格不作保证。 */
function normMac(mac) {
  return String(mac || '').toUpperCase().replace(/\s/g, '')
}

export default {
  data() {
    return {
      bizTypes: BIZ, bizIdx: 0,
      bills: [], billIdx: 0,
      lines: [], picked: [],
      templates: [], tplIdx: 0,
      preview: [], missing: [],
      binding: null, devices: [], scanning: false, bindPop: false,
      connected: false, native: false,
      // 打印权限（base:label:print）：在 onShow 里从 getAppPermissions() 读一次存成数据字段。
      // **不能写成 computed** —— 存储不是响应式源，computed 第一次求值后就永久缓存，
      // H5 keep-alive 换账号回来还会是旧值（v1.1.55 的 perm 口径教训）。
      printPerm: false,
      dataLoaded: false,
      busy: false, lastError: '', lastTspl: '',
      rawCount: 0, loadedOnce: false
    }
  },
  computed: {
    connText() { return this.native ? (this.connected ? '已连接' : '未连接') : '非原生环境' },
    canPrint() { return this.native && this.printPerm && !!this.binding && !!this.currentTpl && !this.busy },
    currentTpl() { return this.templates[this.tplIdx] || null },
    currentBill() { return this.bills[this.billIdx] || null },
    /** 模板份数（只用于按钮文案）。真份数在每段指令末尾的 PRINT n,1 里，A7 写进模板字段。 */
    tplCopies() { return (this.currentTpl && this.currentTpl.copies) || 1 },
    scanHint() {
      if (this.scanning) return '扫描中…'
      if (this.rawCount) {
        return '扫到 ' + this.rawCount + ' 台但都没名字：IP-803 未配对时可能无名广播（docs/34 坑 2），'
          + '请先在系统蓝牙设置里和打印机配对一次，让它带上名字'
      }
      return '没扫到：确认机器已通电、未被别的手机占用'
    }
  },
  onLoad() {
    // 页面级守卫（与 count/index.vue 同一先例）：utils/permission.js 已把本页登记进
    // PAGE_PERMS + SENSITIVE_PAGES，但 App 端没有全局路由拦截器，**页面自己不调就等于裸路由可进**。
    // 页级门禁按「读」口径 base:label:list（能选单/预览），打印按钮另按 base:label:print 卡 ——
    // 与本任务不改 sql/156 的约束一致（156 只镜像出 list/print 两个 F 载体，:list 是单据查看角色都有的那一半）。
    if (!guardPage('base')) return
    if (!checkPagePermission('/pages/base/label-print')) {
      uni.showToast({ title: '无访问权限', icon: 'none' })
      setTimeout(() => { try { uni.switchTab({ url: '/pages/dashboard/index' }) } catch (e) {} }, 500)
    }
  },
  async onShow() {
    this.native = nativeReady()
    this.binding = getBinding()
    // App 端纯权限口径（v1.1.55 hotfix-3 R9）：erp_menus 派生，不是 PC+APP 混合的 getPermissions()
    this.printPerm = isAdmin()
      || (getAppPermissions().includes('base:label:print') && isPermEnabled('base', 'base:label:print'))
    // 并发请求各自 try/catch，一个失败不吃掉其它已拿到的数据（v1.1.145 教训）→ 不用 Promise.all。
    // 顺序有讲究：loadBills 之后链式 loadLines 要用 billIdx，loadTemplates 决定 templateId。
    await this.syncStatus()
    if (!this.dataLoaded) await this.reloadLists()
  },
  methods: {
    body() {
      const b = this.currentBill
      return {
        bizType: this.bizTypes[this.bizIdx].value,
        templateId: this.currentTpl ? this.currentTpl.id : null,
        bizId: b ? b.id : null,
        // 没勾明细行就发空数组 —— 服务端把「空」当作整单（A9/A5），不是"全部行"
        lineIds: this.picked.map(i => this.lines[i].id)
      }
    },
    onBiz(e) {
      this.bizIdx = Number(e.detail.value)
      this.tplIdx = 0
      this.picked = []
      this.lines = []
      this.preview = []
      this.billIdx = 0
      // 换类型 = 换单据源 + 换模板集，两份名单都要重取（模板在前：loadBills 链式带出的行/预览口径要用 templateId）
      this.dataLoaded = false
      this.reloadLists()
    },
    async reloadLists() {
      this.dataLoaded = true
      await this.loadTemplates()
      await this.loadBills()
    },
    onBill(e) { this.billIdx = Number(e.detail.value); this.picked = []; this.loadLines() },
    onTpl(e) { this.tplIdx = Number(e.detail.value) },
    toggleLine(i) {
      this.picked = this.picked.includes(i) ? this.picked.filter(x => x !== i) : this.picked.concat(i)
    },
    async loadBills() {
      try {
        const r = await labelApi.bills({ bizType: this.bizTypes[this.bizIdx].value, limit: 50 })
        this.bills = (r || []).map(x => ({ id: x.id, label: x.billNo || String(x.id) }))
        if (this.billIdx >= this.bills.length) this.billIdx = 0
        // 选中单之后立刻链式取明细行：整单/按行的口径在这里就定下来，
        // 不等用户去点「预览」才发现行名单是空的。
        if (this.bills.length) await this.loadLines()
      } catch (e) { this.bills = []; this.lines = []; this.lastError = '单据加载失败: ' + errText(e) }
      finally { this.loadedOnce = true }
    },
    async loadLines() {
      this.lines = []
      this.picked = []
      const b = this.currentBill
      if (!b) return
      try {
        const r = await labelApi.lines({ bizType: this.bizTypes[this.bizIdx].value, bizId: b.id })
        // 品名键位两种形状都认（R-B4-10，不再赌单键名）：
        //   · `/base/label/lines` 这条 HTTP 端点回 {id, name, qty, batchNo} —— 键名是 `name`
        //     （BaseLabelController.java:381-388 的 line()，本树实测该文件共 395 行）；
        //   · 标签上下文装配层的同类字段叫 `productName`
        //     （context/RawPalletLabelContextLoader.java:54、FgRollLabelContextLoader.java:43、LabelFieldDict）。
        // 只写一个键名，另一条路径就会静默渲染成空品名（工人看不出来，纸面也看不出来）。
        this.lines = (r || []).map(x => ({
          id: x.id, label: (x.name || x.productName || '') + ' ' + (x.batchNo || '') + ' ' + (x.qty ?? '')
        }))
      } catch (e) { this.lastError = '明细加载失败: ' + errText(e) }
    },
    async loadTemplates() {
      try {
        const r = await labelApi.templates(this.bizTypes[this.bizIdx].value)
        this.templates = (r || []).map(t => ({
          id: t.id, label: (t.isDefault ? '★ ' : '') + (t.templateName || t.templateCode), copies: t.copies
        }))
        // 换类型后旧下标可能越界，钳回 0（列表已按 isDefault 优先，0 就是默认模板位）
        if (this.tplIdx >= this.templates.length) this.tplIdx = 0
      } catch (e) { this.templates = []; this.lastError = '模板加载失败: ' + errText(e) }
    },
    async loadPreview() {
      if (!this.body().bizId) { uni.showToast({ title: '先选单据', icon: 'none' }); return }
      this.busy = true
      try {
        const r = await labelApi.preview(this.body())
        this.preview = r || []
        this.missing = this.preview.flatMap(p => p.missingRequired || [])
        this.lastError = ''
      } catch (e) {
        uni.showToast({ title: '预览失败: ' + errText(e), icon: 'none' })
      } finally { this.busy = false }
    },
    /**
     * 预览只是版式：点阵坐标按各自标签的点宽等比缩到 320px（80×60mm @203dpi = 640×480 点，
     * 换算式 dots = floor(mm × trueDpi / 25.4)）。字号取不到打印机点阵字库，屏显只保证不误导版式。
     */
    canvasStyle(r) {
      const w = r.widthDots || 640
      const h = r.heightDots || 480
      return { width: VIEW_W + 'px', height: Math.round(VIEW_W * (h / w)) + 'px' }
    },
    styleOf(el, r) {
      const k = VIEW_W / ((r && r.widthDots) || 640)
      return {
        left: ((el.x || 0) * k) + 'px',
        top: ((el.y || 0) * k) + 'px',
        fontSize: Math.max(7, Math.round((el.fontSize || 12) * k)) + 'px',
        fontWeight: el.bold ? '700' : '400'
      }
    },
    showText(el) {
      if (el.type === 'barcode') return '▍条码 ' + (el.content || '')
      if (el.type === 'qrcode') return '▦二维码 ' + (el.content || '')
      return el.content || ''
    },
    async openBind() {
      this.bindPop = true
      if (!this.native) { uni.showToast({ title: 'H5 不能蓝牙扫描', icon: 'none' }); return }
      // 本机蓝牙运行时授权闸（Task B4 裁定 R-B4-2 的落点）：插件的 scan()/connect()/print()
      // 进门都过 ensureBlePermission(...)，没授权一律 reject「…请点「申请蓝牙权限」」
      // （LabelBlePlugin.java:295 / :391 / :673），而插件确实备了这个入口（:243-244 @PluginMethod ensurePermissions）。
      // 取证页有那颗按钮，生产页没有 —— 不在扫描前要一次权限，车间的新手机（Android 12+ 的「附近的设备」）
      // 就是**永久扫不到设备**。已授权时 ensurePermissions() 立即 resolve，不会重复弹窗（:245-247）。
      // 口径：自动申请，只在被拒时解释去哪开（系统设置 → 应用 → 权限 → 附近的设备/蓝牙）。
      try { await ensurePermissions() }
      catch (e) {
        uni.showModal({
          title: '蓝牙权限被拒',
          content: errText(e) + '\n请到 系统设置 → 应用 → 权限 里允许「附近的设备」/「蓝牙」（Android 11 以下是「定位」），再回来点「绑定/换机」。',
          showCancel: false
        })
        return
      }
      this.scanning = true; this.devices = []; this.rawCount = 0
      try {
        const all = await scan(6000)
        this.rawCount = all.length
        // 无名设备滤掉（与取证页同口径）：车间一台手机上周边往往十几台 BLE，
        // 全列出来点不动；IP-803 配对后带名字（docs/34：IP-803BT_xxxx_BLE）。
        this.devices = all.filter(d => d && d.mac && d.name)
      }
      catch (e) { uni.showToast({ title: '扫描失败: ' + errText(e), icon: 'none' }) }
      finally { this.scanning = false }
    },
    async pick(d) {
      this.bindPop = false
      setBinding(d.mac, d.name)
      this.binding = getBinding()
      await this.reconnect()
    },
    /**
     * 只问桥面现状，链路已经活着就**不重建** —— 插件的 connect() 是「先拆旧链再新建」
     * （LabelBlePlugin.java:412-431），照 brief 每次 onShow 都 connect 一遍的话，
     * 连续打印时每次切回来都要重连几秒，还会把上一笔在途的写打断。
     * 没连上（或连的是别的 MAC）才自动重连一次，与「进页面就该能用」的直觉一致。
     *
     * Task B4 返修：返回值是**结论**（true=已连上 / false=连不上），不再 `return` 掉进 void。
     * 调用方（onShow / pick）要靠它区分「已连接」和「连不了」，静默吞掉就等于
     * UI 上还挂着上一轮的 connected=true、工人对着台没连上的机器点打印。
     */
    async syncStatus() {
      if (!this.native || !this.binding) { this.connected = false; return false }
      const want = normMac(this.binding.mac)
      let s = null
      try { s = await status() } catch (e) { s = null }
      this.connected = !!(s && s.connected)
      if (this.connected && normMac(s.mac) === want) return true
      return await this.reconnect()
    },
    async reconnect() {
      if (!this.native || !this.binding) { this.connected = false; return false }
      // 这里**不再**预调 ensurePermissions()：插件的 connect() 自己就过 ensureBlePermission 闸
      // （LabelBlePlugin.java:391），运行时授权已由 openBind() 在扫描前要到手的，重复申请只是
      // 在每次重连前多压一次异步；真被拒时下面 connect() 的 reject 会带着插件写好的原话出来。
      try {
        await connect(this.binding.mac)
        this.connected = true
        return true
      } catch (e) {
        this.connected = false
        uni.showToast({ title: '连接失败: ' + errText(e), icon: 'none', duration: 3000 })
        return false
      }
    },
    /**
     * 显式断开（Task B4 裁定 R-B4-7）：车间一台机器常被两台手机抢，
     * 只靠系统蓝牙设置解绑太绕，页面要给一颗「把链还给别人」的按钮。
     * 绑定关系（storage 里的 mac/name）**保留**，下次点「重连」就能连回来。
     */
    async doDisconnect() {
      if (!this.native) { uni.showToast({ title: 'H5 没有蓝牙链路可断', icon: 'none' }); return }
      try { await disconnect(); this.connected = false; uni.showToast({ title: '已断开', icon: 'none' }) }
      catch (e) { uni.showToast({ title: '断开失败: ' + errText(e), icon: 'none' }) }
    },
    /** 一次发送 = 一份完整指令包（里面已含 明细行数 × 模板份数 的 PRINT），页面绝不再乘一遍份数。 */
    async doPrint() {
      const body = this.body()
      if (!body.bizId || !body.templateId) { uni.showToast({ title: '单据和模板都要选', icon: 'none' }); return }
      this.busy = true
      let content = null
      let text = ''
      let n = 1
      try {
        const r = await labelApi.tspl(body)
        content = (r.contentsBase64 || [])[0]
        text = r.previewText || ''
        n = r.copies || 1
        if (!content) throw new Error('后端没回指令内容')
        this.lastTspl = text
        const rs = await send(content)
        this.connected = true
        // missingAcks>0 就是「打一半」的前兆（docs/34 的分片判据），必须当场看得见
        const ack = rs && rs.missingAcks > 0 ? ' · 有 ' + rs.missingAcks + ' 片没等到回执，若打不全请调大片间隔' : ''
        uni.showToast({ title: '已发送 ' + ((rs && rs.sentBytes) || 0) + ' 字节 · ' + n + ' 张' + ack, icon: 'none', duration: 3000 })
        await this.reportLog('SUCCESS', n, text, '')
      } catch (e) {
        const msg = errText(e)
        uni.showModal({ title: '打印失败', content: msg + '\n可把下面的 TSPL 原文复制去 PC 导出同一张', showCancel: false })
        await this.reportLog('FAIL', n, content ? text : '', msg)
      } finally { this.busy = false }
    },
    /**
     * 打完必须回报日志：XK 检查时「谁在手机上打错了什么」只能靠这一行落库（docs/32 §5）。
     * 页面**不预判授权、不发探测请求** —— perm 由服务端 `@SaCheckPermission` 强制，
     * 这里的 catch 只保证「日志失败绝不挡车间出纸」，不再叠一个 toast 打扰操作。
     *
     * content 送 **previewText（TSPL 指令原文）**，不是 base64 那坨字节：
     *   · DDL 注释就是「渲染结果快照 (TSPL 原文或飞鹅指令)」（sql/155:50）；
     *   · B3 的 EXPORT 行落的也是 previewText（BaseLabelController.java:249-250
     *     `printService.logSuccess(..., preview.toString())`）—— 同一张表里 BT_TSPL 与 EXPORT
     *     必须同形，否则「手机打的那份」和「PC 导出的那份」在审计材料里对不上；
     *   · base64 既读不出来，也让 content_hash 变成「字节包装的哈希」而不是内容指纹。
     */
    async reportLog(st, n, content, errMsg) {
      try {
        await labelApi.log({ ...this.body(), bizNo: this.currentBill ? this.currentBill.label : null,
          printerName: this.binding ? this.binding.name : null, printerMac: this.binding ? this.binding.mac : null,
          copies: n, content, status: st, errorMsg: errMsg || null })
      } catch (e) { /* 日志失败绝不再叠一个 toast 打扰车间操作 */ }
    }
  }
}
</script>

<style>
.page { padding: 12px; background: #f5f6f8; min-height: 100vh; }
.card { background: #fff; border-radius: 10px; padding: 10px 12px; margin-bottom: 10px; }
.row { display: flex; align-items: center; margin: 6px 0; }
.row.col { display: block; }
.lab { color: #888; font-size: 13px; margin-right: 8px; }
.val { font-size: 15px; flex: 1; }
.lrow { padding: 6px 0; display: flex; }
.cbox { width: 22px; }
.ltext { flex: 1; font-size: 14px; }
.btns { display: flex; gap: 8px; margin-top: 8px; }
.btns.big { margin-top: 14px; }
.primary { flex: 1; background: #2f6fed; color: #fff; }
.warn { color: #b26a00; font-size: 12px; margin-top: 6px; }
.canvas { position: relative; width: 320px; height: 240px; margin: 8px 0;
  background: #fff; border: 1px solid #cfd4dc; }
.cap { position: absolute; left: 2px; bottom: -16px; color: #999; font-size: 11px; }
.el { position: absolute; white-space: nowrap; }
.miss { color: #d03050; }
.missnote { color: #d03050; font-size: 12px; }
.tspl { width: 100%; min-height: 120px; border: 1px solid #ddd; padding: 8px; font-size: 11px; }
.pop { position: fixed; left: 0; top: 0; right: 0; bottom: 0; background: rgba(0,0,0,.4); }
.popbody { margin: 12vh 18px; background: #fff; border-radius: 10px; padding: 12px; }
.devlist { max-height: 46vh; }
.dev { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #eee; }
.mac { color: #999; font-size: 12px; }
</style>
