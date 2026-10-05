<template>
  <view class="container">
    <!-- 对话助手 -->
    <view class="card">
      <view class="card-head">
        <text class="title">🤖 AI 助手</text>
        <text class="muted">查数据 / 查文档; 写操作只给建议, 需 PC 端确认</text>
      </view>
      <scroll-view scroll-y class="chat" :scroll-top="chatScroll">
        <view v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <view class="bubble">
            <text class="btext">{{ m.text }}</text>
            <view v-if="m.actions && m.actions.length" class="propose">
              <view v-for="(p, j) in m.actions" :key="j" class="propose-item">
                <text class="psum">{{ p.summary }}</text>
                <text class="pnote">写操作 — 请在 PC 端 AI 助手确认执行</text>
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
import api from '../../api/index.js'
import { applyTabBar } from '../../utils/permission.js'

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
    text: '你好, 我可以查库存/客户/出库单, 也能查使用手册/部署文档, 还能给补货建议。\n试试:「透明胶带现在库存多少」「哪些商品该补货了」'
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
    const actions = (d.proposedActions || []).map(s => {
      try { return JSON.parse(s) } catch (e) { return { summary: s } }
    })
    messages.value.push({ role: 'assistant', text: d.answer || '(无返回)', actions })
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

onMounted(() => {
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
.pnote { font-size: 11px; color: #e6a23c; }
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
</style>
