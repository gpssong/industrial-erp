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
        <input v-model="q" class="qinput" placeholder="如: 透明胶带现在库存多少 / 哪些该补货了" @confirm="send" />
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
.inputrow { display: flex; gap: 6px; margin-top: 10px; }
.qinput { flex: 1; border: 1px solid #dcdfe6; border-radius: 6px; padding: 6px 8px; font-size: 13px; }
.sendbtn { flex: 0 0 auto; background: var(--primary); color: #fff; border-radius: 6px; font-size: 13px; padding: 0 14px; line-height: 30px; margin: 0; }
.sendbtn[disabled] { opacity: .6; }
.replenish-bar { display: flex; gap: 6px; margin-bottom: 8px; }
</style>
