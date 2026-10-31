<template>
  <view class="container">
    <!-- 对话助手 -->
    <view class="card">
      <view class="card-head">
        <text class="title">🤖 AI 助手</text>
        <text class="muted">查数据 / 查文档; 写操作先给提议, 点「确认执行」才真改数据</text>
      </view>
      <scroll-view scroll-y class="chat" :scroll-top="chatScroll">
        <view v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <view class="bubble" :class="{ wide: m.tables && m.tables.length }">
            <text class="btext">{{ m.text }}</text>
            <!-- v1.1.144: 后端算好的销售汇总表 (数字不经模型转述), 横向可滚, 附下载 -->
            <view v-if="m.tables && m.tables.length" class="tables">
              <view v-for="(t, k) in m.tables" :key="'tb' + k" class="tbl">
                <view class="tbl-head">
                  <text class="tbl-title">{{ t.title }}</text>
                  <button class="pbtn" @click="downloadTable(t)">下载</button>
                </view>
                <scroll-view scroll-x class="tscroll">
                  <view class="trow thead">
                    <text v-for="c in (t.columns || [])" :key="c.key" class="tc" :style="cellStyle(c.key)">{{ c.label }}</text>
                  </view>
                  <view v-for="(r, ri) in (t.rows || [])" :key="ri" class="trow">
                    <text v-for="c in (t.columns || [])" :key="c.key" class="tc" :style="cellStyle(c.key)">{{ r[c.key] }}</text>
                  </view>
                  <view v-if="t.totals" class="trow tfoot">
                    <text v-for="c in (t.columns || [])" :key="c.key" class="tc" :style="cellStyle(c.key)">{{ t.totals[c.key] }}</text>
                  </view>
                </scroll-view>
                <text v-if="t.truncated" class="tnote">已达行数上限 ({{ t.count }} 行), 可让 AI 按月拆分或指定客户再查</text>
                <text v-if="t.note" class="tnote">{{ t.note }}</text>
              </view>
            </view>
            <view v-if="m.actions && m.actions.length" class="propose">
              <view v-for="(p, j) in m.actions" :key="j" class="propose-item">
                <text class="psum">{{ p.summary }}</text>
                <view class="pacts">
                  <button class="pbtn" :disabled="spent(p)" @click="execPropose(p)">确认执行</button>
                  <button class="pbtn ghost" :disabled="!!p.executing" @click="m.actions.splice(j,1)">取消</button>
                </view>
                <text v-if="p.executed" class="pdone">{{ p.executed }}</text>
              </view>
            </view>
          </view>
        </view>
        <view v-if="busy" class="msg assistant"><view class="bubble"><text class="btext">AI 正在分析…</text></view></view>
      </scroll-view>
      <view class="inputrow">
        <input v-model="q" class="qinput" placeholder="如: 透明胶带现在库存多少 / 或按住 🎤 说话"
               @confirm="send" @focus="stopSpeech" />
        <button v-if="speechOk" class="micbtn" :class="{rec: recording}"
                :disabled="busy"
                @touchstart="startSpeech" @touchend="stopSpeech"
                @touchcancel="stopSpeech" @touchmove.stop
                @mousedown="startSpeech" @mouseup="stopSpeech"
                @mouseleave.stop="stopSpeech">🎤</button>
        <button class="sendbtn" :disabled="busy" @click="send">发送</button>
      </view>
    </view>

    <!-- 补货预测 -->
    <view class="card">
      <view class="card-head">
        <text class="title">📦 补货预测</text>
        <text class="muted">近30天出库 + 库存 + 安全库存</text>
      </view>
      <view class="replenish-bar">
        <input v-model="reKey" class="qinput" placeholder="按商品名/编码过滤" @confirm="loadReplenish" />
        <button class="sendbtn" @click="loadReplenish">查询</button>
      </view>
      <view v-if="reLoading" class="muted" style="padding:8px 0">加载中…</view>
      <view v-else-if="!reList.length" class="muted" style="padding:8px 0">暂无补货建议 (近30天无出库流水或库存充足)</view>
      <view v-else>
        <view v-for="(r, i) in reList" :key="i" class="row" style="padding:8px 0;border-bottom:1px solid #f0f0f0">
          <view>
            <text>{{ r.productName || r.productCode }}</text>
            <text class="muted" style="display:block;font-size:11px">{{ r.productCode }}</text>
          </view>
          <view style="text-align:right">
            <text v-if="r.recommend" style="color:#f56c6c;font-weight:600">建议补 {{ r.suggestQty }}</text>
            <text v-else class="muted">充足</text>
            <text v-if="r.estDaysOfStock != null" class="muted" style="display:block;font-size:11px">可售 {{ r.estDaysOfStock }} 天</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>
<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { registerPlugin } from '@capacitor/core'
import api from '../../api/index.js'
import { isNativeApp } from '../../utils/runtime.js'
import { applyTabBar } from '../../utils/permission.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const NativeShare = registerPlugin('NativeShare')

const messages = ref([])
const q = ref('')
const busy = ref(false)
const chatScroll = ref(0)
// 补货预测
const reKey = ref('')
const reList = ref([])
const reLoading = ref(false)
// v1.1.78: 语音输入
const recording = ref(false)   // 是否正在录音/识别
// 默认 false, 探测后置 true (H5 有 Web Speech 或原生 App 有 RecorderManager)
// 兜底: uni 运行时存在时也开 (旧版 uni-app 可能叫法不同)
const speechOk = ref(
  ((typeof window !== 'undefined') && (window.webkitSpeechRecognition || window.SpeechRecognition)) ||
  (typeof uni !== 'undefined' && (uni.getRecorderManager || typeof uni.startRecord === 'function')) ||
  false
)
// 不挂 ref, 用 let 引用对象 (SpeechRecognition / RecorderManager)
let recog = null
let recogFinal = ''
let rm = null

function detectSpeech() {
  // H5 (浏览器) 端侧优先: Web Speech API, 零后端, 零延迟, 边说边出字
  const SR = (typeof window !== 'undefined') && (window.webkitSpeechRecognition || window.SpeechRecognition)
  if (SR) { speechOk.value = true; return }
  // 原生 App 端: 录完上传后端 ASR (DashScope paraformer)
  if (typeof uni !== 'undefined' && (uni.getRecorderManager || typeof uni.startRecord === 'function')) {
    speechOk.value = true
  }
}

function startSpeech() {
  if (recording.value || busy.value) return
  const SR = (typeof window !== 'undefined') && (window.webkitSpeechRecognition || window.SpeechRecognition)
  if (SR) startWebSpeech()
  else startNativeRecord()
}

function startWebSpeech() {
  const SR = window.webkitSpeechRecognition || window.SpeechRecognition
  recog = new SR()
  recog.lang = 'zh-CN'
  recog.interimResults = true
  recog.continuous = false
  recogFinal = ''
  recog.onresult = (e) => {
    let interim = ''
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const t = e.results[i][0].transcript
      if (e.results[i].isFinal) recogFinal += t; else interim += t
    }
    q.value = recogFinal + interim
  }
  recog.onerror = () => {
    recording.value = false
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: '语音识别失败, 请重试', icon: 'none' })
    }
  }
  recog.onend = () => { recording.value = false; recog = null }
  try {
    recog.start()
    recording.value = true
  } catch (e) {
    recording.value = false
    recog = null
  }
}

function startNativeRecord() {
  if (!rm) rm = uni.getRecorderManager()
  // 幂等清旧回调 (防止连按)
  rm.onStart(() => { recording.value = true })
  rm.onStop((res) => { recording.value = false; uploadAsr(res.tempPath) })
  rm.onError((err) => {
    recording.value = false
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: '录音失败: ' + (err.errMsg || '请检查麦克风权限'), icon: 'none' })
    }
  })
  // m4a=mp4, paraformer 也支持. sampleRate=16000 适配短音频模型.
  rm.start({
    format: 'mp4',
    sampleRate: 16000,
    numberOfChannels: 1,
    frameSize: 64,
    duration: 60000   // 上限 60s, 超过自动停
  })
}

async function uploadAsr(filePath) {
  try {
    const r = await api.aiAsr(filePath)
    const text = (r && r.text) || ''
    if (text) {
      q.value = text
    } else if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: '未识别到内容, 请重说', icon: 'none' })
    }
  } catch (e) {
    if (typeof uni !== 'undefined' && uni.showToast) {
      uni.showToast({ title: (e && e.msg) || '语音上传失败', icon: 'none' })
    }
  }
}

function stopSpeech() {
  if (!recording.value) return
  if (recog) { try { recog.stop() } catch (e) {} ; recog = null }
  if (rm)   { try { rm.stop() }   catch (e) {} }   // onStop 回调里会 uploadAsr
  recording.value = false
}

if (!messages.value.length) {
  messages.value.push({
    role: 'assistant',
    text: '你好, 我可以查库存/客户/出库单、按客户和时间段出销售汇总表 (能下载 CSV), 也能查使用手册, 还能给补货建议、帮你开成品入库单 (点确认才执行)。\n试试:「长晟9月份一共销售多少」「各客户今年销售额按月」「透明胶带现在库存多少」「哪些商品该补货了」'
  })
}

async function send() {
  const question = q.value.trim()
  if (!question || busy.value) return
  q.value = ''
  messages.value.push({ role: 'user', text: question })
  busy.value = true
  await nextTick()
  try {
    const r = await api.aiAgentChat(question)
    const d = (r && r.data) || r || {}
    // 同 PC 端: 19 位雪花 id 裸 JSON.parse 会掉精度, 先转字符串
    const actions = (d.proposedActions || []).map(parseProposal)
    // v1.1.144: 后端透传的结构化表格 (getSalesSummary 的返回体), 直接渲染 + 可下载 CSV
    messages.value.push({ role: 'assistant', text: d.answer || '(无返回)', actions, tables: d.tables || [] })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: 'AI 调用失败: ' + ((e && (e.msg || e.message)) || '未知') })
  } finally {
    busy.value = false
    await scrollBottom()
  }
}

async function loadReplenish() {
  reLoading.value = true
  try {
    const r = await api.aiReplenishSuggest(reKey.value || '', 30)
    reList.value = (r && r.data) || r || []
  } catch (e) {
    reList.value = []
  } finally {
    reLoading.value = false
  }
}

function scrollBottom() {
  return nextTick(() => { chatScroll.value = 99999 })
}

// ===== v1.1.144: 销售汇总表格展示 + CSV 下载 =====

const COL_W = { period: 92, customerName: 190, billCount: 96, totalQty: 118, totalAmount: 128 }
function cellStyle(key) {
  const w = COL_W[key] || 120
  return { width: w + 'px', minWidth: w + 'px' }
}

/**
 * 下载走后端 /ai/sales-summary/csv (不是前端拼 CSV): 口径和销售数据权限只在后端判一次,
 * 而且屏上的表和下载的文件是同一个算法出的同一份数。
 * 原生 APK 里 <a download> 不可用, 交给 NativeShare 带 cookie 下载后调起系统分享。
 */
async function downloadTable(t) {
  const url = api.aiSalesSummaryCsvUrl(t.params || {})
  if (!isNativeApp()) {
    try {
      const a = document.createElement('a')
      a.href = url
      a.download = (t.fileName || '销售汇总') + '.csv'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      toast('已开始下载 ' + (t.fileName || '销售汇总') + '.csv')
    } catch (e) {
      toast('下载失败: ' + ((e && e.message) || '未知'))
    }
    return
  }
  try {
    await NativeShare.shareFile({
      url,
      fileName: (t.fileName || '销售汇总') + '.csv',
      mimeType: 'text/csv',
      title: t.title || '销售汇总'
    })
    toast('已下载, 可选择保存或发送')
  } catch (e) {
    const msg = (e && (e.message || e.errMsg)) || '下载失败'
    if (msg.indexOf('取消') < 0 && msg.indexOf('cancel') < 0) toast(msg)
  }
}

// ---- v1.1.143: App 端也能确认执行写操作 (以前只能去 PC 点确认) ----
// 白名单与 PC 端 report/Ai.vue 完全一致; 模型自创 action 别名先归一。
const ACTION_ALIAS = {
  addPurIn: 'addPurIn', addPurReceipt: 'addPurIn', addOtherInbound: 'addPurIn',
  addPurInbound: 'addPurIn', createPurIn: 'addPurIn', createPurReceipt: 'addPurIn',
  purchaseInbound: 'addPurIn', addPurchaseIn: 'addPurIn',
  checkSalDelivery: 'checkSalDelivery', approveSalDelivery: 'checkSalDelivery', auditSalDelivery: 'checkSalDelivery',
  addPrdFinishedIn: 'addPrdFinishedIn', addFinishedIn: 'addPrdFinishedIn', addPrdIn: 'addPrdFinishedIn',
  createFinishedIn: 'addPrdFinishedIn', finishedInbound: 'addPrdFinishedIn', productionInbound: 'addPrdFinishedIn',
  addProductionIn: 'addPrdFinishedIn', prdFinishedInAdd: 'addPrdFinishedIn',
  checkPrdFinishedIn: 'checkPrdFinishedIn', checkFinishedIn: 'checkPrdFinishedIn',
  approveFinishedIn: 'checkPrdFinishedIn', auditFinishedIn: 'checkPrdFinishedIn', auditPrdFinishedIn: 'checkPrdFinishedIn'
}

function toast(title) {
  if (typeof uni !== 'undefined' && uni.showToast) uni.showToast({ title, icon: 'none' })
}

function parseProposal(s) {
  try {
    return JSON.parse(String(s).replace(/([:,\[]\s*)(\d{16,})(?=\s*[,}\]])/g, '$1"$2"'))
  } catch (e) {
    return { summary: s }
  }
}

// 成功后锁死按钮: 再点一次就重复建一张草稿单; 失败的原样留着让用户重试
function spent(p) {
  return !!p.executing || String(p.executed || '').indexOf('✓') === 0
}

async function execPropose(p) {
  if (spent(p)) return
  p.executing = true
  p.executed = '执行中…'
  try {
    if (!p.params) {
      p.executed = '这条提议缺少参数 (模型没按格式输出), 请重新描述需求再试'
      toast(p.executed)
      return
    }
    const action = ACTION_ALIAS[p.action] || p.action
    switch (action) {
      case 'checkSalDelivery':
        await api.salesDeliveryCheck(p.params.id)
        p.executed = '✓ 出库单已审核'
        break
      case 'addPurIn':
        if (p.params.supplierId == null || p.params.supplierId === '') {
          p.executed = '缺供应商: 采购入库单必须有供应商, 请让 AI 先查一次供应商列表'
          toast(p.executed)
          break
        }
        await api.purchaseReceiptAdd(p.params)
        p.executed = '✓ 采购入库单已生成 (草稿, 审核后才会入库)'
        break
      case 'addPrdFinishedIn': {
        if (p.params.productId == null || p.params.productId === '') {
          p.executed = '缺商品: 成品入库单必须有具体商品, 请告诉我商品名称或编码'
          toast(p.executed)
          break
        }
        if (p.params.warehouseId == null || p.params.warehouseId === '') {
          p.executed = '缺仓库: 成品入库单必须指定入库仓库, 请说明入到哪个仓'
          toast(p.executed)
          break
        }
        if (!(Number(p.params.qty) > 0)) {
          p.executed = '数量必须大于 0: 请说明实际生产入库数量'
          toast(p.executed)
          break
        }
        await api.prdFinishedInAdd(p.params)
        p.executed = '✓ 成品入库单已生成 (草稿, 审核后库存才入账); 可以说「审核这张单」'
        break
      }
      case 'checkPrdFinishedIn':
        if (p.params.id == null || p.params.id === '') {
          p.executed = '缺入库单 id: 请先让 AI 查一下最近的成品入库单'
          toast(p.executed)
          break
        }
        await api.prdFinishedInCheck(p.params.id)
        p.executed = '✓ 成品入库单已审核, 库存已入账'
        break
      default:
        p.executed = '暂不支持自动执行的写操作: ' + p.action
          + ' (白名单: 出库单审核 / 采购入库单 / 成品入库单 建单+审核)'
    }
    if (String(p.executed).indexOf('✓') === 0) toast(p.executed)
  } catch (e) {
    p.executed = '执行失败: ' + ((e && (e.msg || e.message)) || '未知')
    toast(p.executed)
  } finally {
    p.executing = false
  }
}

onMounted(() => {
  // v1.1.80+: 模块停用守卫
  if (!guardPage('report')) return
  applyTabBar()
  detectSpeech()
  loadReplenish()
})
</script>
<style scoped>
.container { padding: 10px; }
.card { background: #fff; border-radius: 8px; padding: 12px; margin-bottom: 12px; box-shadow: 0 1px 2px rgba(0,0,0,.05); }
.card-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
.title { font-size: 15px; font-weight: 600; }
.muted { color: #909399; font-size: 12px; }
.chat { max-height: 240px; min-height: 120px; overflow-y: auto; }
.msg { margin-bottom: 8px; display: flex; }
.msg.user { justify-content: flex-end; }
.bubble { max-width: 85%; background: #f4f4f5; border-radius: 8px; padding: 8px 10px; }
.msg.assistant .bubble { background: #ecf5ff; }
.btext { font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.propose { margin-top: 8px; border-top: 1px dashed #c0c4cc; padding-top: 6px; }
.propose-item { margin-bottom: 6px; }
.psum { font-size: 12px; font-weight: 500; display: block; }
.pacts { display: flex; gap: 6px; margin-top: 4px; }
.pbtn { background: var(--primary); color: #fff; border-radius: 5px; font-size: 12px; line-height: 26px; height: 26px; padding: 0 10px; margin: 0; }
.pbtn.ghost { background: #fff; color: #606266; border: 1px solid #dcdfe6; }
.pbtn[disabled] { opacity: .6; }
.pdone { font-size: 11px; color: #67c23a; display: block; margin-top: 4px; }
.inputrow { display: flex; gap: 6px; margin-top: 10px; align-items: center; }
.qinput { flex: 1; border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 8px; font-size: 13px; }
.sendbtn { flex: 0 0 auto; background: var(--primary); color: #fff; border-radius: 6px; font-size: 13px; padding: 0 14px; line-height: 30px; margin: 0; }
.sendbtn[disabled] { opacity: .6; }
.micbtn {
  flex: 0 0 auto;
  width: 36px; height: 30px;
  background: #fff; color: #303133;
  border: 1px solid #dcdfe6; border-radius: 6px;
  font-size: 16px; line-height: 28px; padding: 0;
  margin: 0;
  -webkit-user-select: none; user-select: none;
  transition: background .15s, color .15s;
}
.micbtn[disabled] { opacity: .5; }
.micbtn.rec { background: #f56c6c; color: #fff; border-color: #f56c6c; }
.replenish-bar { display: flex; gap: 6px; margin-bottom: 8px; }
/* v1.1.144: AI 销售汇总表格 (手机宽度放不下 5 列, 整条气泡通栏 + 表格横向滚) */
.bubble.wide { max-width: 100%; width: 100%; }
.tables { margin-top: 8px; }
.tbl { background: #fff; border: 1px solid #ebeef5; border-radius: 6px; padding: 8px; margin-top: 6px; }
.tbl-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 6px; }
.tbl-title { font-size: 12px; font-weight: 600; flex: 1; }
.tscroll { width: 100%; white-space: nowrap; }
.trow { display: flex; border-bottom: 1px solid #f5f5f5; }
.trow.thead { background: #f8f9fb; font-weight: 600; }
.trow.tfoot { background: #fdf6ec; font-weight: 600; }
.tc { font-size: 12px; line-height: 30px; padding: 0 6px; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tnote { display: block; font-size: 11px; color: #909399; margin-top: 6px; line-height: 1.5; }
</style>
