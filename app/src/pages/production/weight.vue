<template>
  <view class="container">
    <!-- ========== 采集器选择 (chip 横滚) ========== -->
    <view class="card picker-card">
      <view class="picker-title">选择采集器</view>
      <scroll-view scroll-x="true" class="dev-scroll" :show-scrollbar="false">
        <view class="dev-chips">
          <view v-for="d in devices" :key="d.id"
            :class="['chip', deviceId === d.id ? 'chip-active' : '']"
            @click="onPickDevice(d)">
            {{ d.deviceCode || d.deviceName || d.id }}
          </view>
        </view>
      </scroll-view>
      <view v-if="loadingDev" class="muted">采集器加载中...</view>
      <view v-else-if="!devices.length" class="muted">
        暂无采集器 — ESP32 上报后自动注册
      </view>
      <view v-else-if="currentDev" class="dev-meta">
        <text class="meta-name">{{ currentDev.deviceName || currentDev.deviceCode }}</text>
        <text :class="['dot', currentDev.onlineStatus === 'ONLINE' ? 'dot-on' : 'dot-off']"></text>
        <text class="muted">{{ currentDev.onlineStatus || 'OFFLINE' }}</text>
        <text class="muted" v-if="currentDev.workshop">· {{ currentDev.workshop }}</text>
        <text class="muted" v-if="currentDev.lastReportTime">
          · 最后上报 {{ fmtT(currentDev.lastReportTime) }}
        </text>
      </view>
    </view>

    <!-- ========== 实时 KPI (8 宫格, 对齐 MCGS 屏 12 量里的核心 8 个) ========== -->
    <view class="card" v-if="latest">
      <view class="row card-head-row">
        <text class="title" style="margin-bottom:0">实时状态</text>
        <text class="muted">{{ lastRefresh ? '刷新 ' + fmtT(lastRefresh) : '未加载' }}</text>
      </view>
      <view class="kpi-grid">
        <view class="kpi-cell" v-for="k in kpiCells" :key="k.key">
          <view :class="['kpi-val', k.over ? 'val-bad' : '']">{{ k.value }}</view>
          <view class="kpi-unit">{{ k.unit }}</view>
          <view class="kpi-lbl">{{ k.label }}</view>
        </view>
      </view>
      <!-- 迷你趋势条: 最近 40 条实测米重, 纯 view 渲染 (App 端不引 echarts) -->
      <view class="trend-wrap" v-if="trend.length > 1">
        <view class="trend-bars">
          <view v-for="(t, i) in trend" :key="i"
            :class="['trend-bar', t.over ? 'bar-bad' : 'bar-ok']"
            :style="{ height: barHeight(t) }"></view>
        </view>
        <view class="trend-foot muted">
          最近 {{ trend.length }} 条实测米重 · 绿=正常 橙=超差
        </view>
      </view>
    </view>

    <!-- ========== 统计 (每通道) ========== -->
    <view class="card" v-if="stats.length">
      <view class="title">通道统计</view>
      <view v-for="s in stats" :key="s.channelNo" class="stat-row">
        <view class="stat-line">
          <text class="stat-name">通道 {{ s.channelNo }}</text>
          <text class="muted">均值 {{ fmtN(s.avgWeight) }} / 最小 {{ fmtN(s.minWeight) }} / 最大 {{ fmtN(s.maxWeight) }}</text>
        </view>
        <view class="stat-line">
          <text class="muted">样本 {{ s.sampleCount }} 条</text>
          <text :class="Number(s.overRatePct) > 10 ? 'val-bad' : 'muted'">
            超标 {{ s.overCount }} 次 / {{ s.overRatePct }}%
          </text>
        </view>
      </view>
    </view>

    <!-- ========== 采样明细 ========== -->
    <view class="card">
      <view class="row card-head-row">
        <text class="title" style="margin-bottom:0">采样明细</text>
        <view class="row">
          <text class="muted">共 {{ total }} 条</text>
          <button class="btn-mini" @click="loadSamples">刷新</button>
        </view>
      </view>

      <view v-if="!samples.length && !loadingSamples" class="empty">暂无采样数据</view>
      <view v-if="loadingSamples && !samples.length" class="empty">加载中...</view>

      <view v-for="s in samples" :key="s.id" class="sample-item">
        <view class="row">
          <view style="flex:1;min-width:0">
            <text class="sample-time">{{ s.collectTime || '—' }}</text>
            <text class="muted"> · 通道 {{ s.channelNo }}</text>
          </view>
          <text :class="['sample-w', s.isOver ? 'val-bad' : 'val-ok']">
            {{ fmtN(s.actualWeight) }} g
          </text>
        </view>
        <view class="sample-grid">
          <view class="sg-item"><text class="sg-lbl">设定</text><text class="sg-val">{{ fmtN(s.setWeight) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">偏差</text>
            <text :class="['sg-val', s.isOver ? 'val-bad' : '']">{{ fmtN(s.deviation) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">偏差%</text><text class="sg-val">{{ fmtN(s.deviationPct) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">线速</text><text class="sg-val">{{ fmtN(s.lineSpeed) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">计米长</text><text class="sg-val">{{ fmtN(s.rollLength) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">计米重</text><text class="sg-val">{{ fmtN(s.outputKg) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">机产</text><text class="sg-val">{{ fmtN(s.machineYield) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">当前重</text><text class="sg-val">{{ fmtN(s.currentWeight) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">上牵引</text><text class="sg-val">{{ fmtN(s.upperHz) }}</text></view>
          <view class="sg-item"><text class="sg-lbl">克重修正</text><text class="sg-val">{{ fmtN(s.gaugeFix) }}</text></view>
        </view>
        <view class="sample-foot muted" v-if="s.prdOrderNo">工单 {{ s.prdOrderNo }}<text v-if="s.productName"> · {{ s.productName }}</text></view>
      </view>

      <view class="pager" v-if="total > pageSize">
        <button class="btn-mini" :disabled="pageNum <= 1" @click="gotoPage(pageNum - 1)">上一页</button>
        <text class="muted">{{ pageNum }} / {{ Math.ceil(total / pageSize) }}</text>
        <button class="btn-mini" :disabled="pageNum >= Math.ceil(total / pageSize)" @click="gotoPage(pageNum + 1)">下一页</button>
      </view>
    </view>

    <!-- ========== 通道配置 (只读展示, App 端不做维护 — 维护走 PC) ========== -->
    <view class="card" v-if="channels.length">
      <view class="title">通道配置</view>
      <view v-for="c in channels" :key="c.id" class="stat-row">
        <view class="stat-line">
          <text class="stat-name">通道 {{ c.channelNo }} {{ c.channelName || '' }}</text>
          <text class="muted">
            寄存器 {{ c.regStart ?? '—' }} · 功能码 {{ c.regType === 3 ? '03' : c.regType === 4 ? '04' : (c.regType ?? '—') }}
          </text>
        </view>
        <view class="stat-line">
          <text class="muted">缩放 {{ c.scale ?? '—' }} · 单位 {{ c.unit || '—' }}</text>
          <text class="muted">设定 {{ fmtN(c.setWeight) }} ±{{ fmtN(c.upperTol) }}/-{{ fmtN(c.lowerTol) }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import api from '../../api/index.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

// ⚠️ v1.1.118 教训: App 端拦截器已 resolve(d.data), r 就是业务数据, **不可再读 r.data**
const devices = ref([])
const deviceId = ref(null)
const loadingDev = ref(false)
const curve = ref([])
const samples = ref([])
const stats = ref([])
const channels = ref([])
const loadingSamples = ref(false)
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)
const lastRefresh = ref(null)

let timer = null

const currentDev = computed(() => devices.value.find(d => d.id === deviceId.value) || null)

// ---- 工具 ----
function fmtT(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(5, 19)
}
// 数值格式化: null/NaN → '—', 整数原样, 小数最多 3 位
function fmtN(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (isNaN(n)) return '—'
  if (Number.isInteger(n)) return String(n)
  return n.toFixed(3).replace(/\.?0+$/, '')
}

// 最新一条采样 (曲线按时间正序返回, 取末尾)
const latest = computed(() => (curve.value.length ? curve.value[curve.value.length - 1] : null))

// KPI 8 宫格 — 覆盖 MCGS 屏 12 量中的核心 8 个
// ⚠️ 缺字段显示 '—' 而不是编造占位文案 (R: 前端兜底文案不能当显示值)
const kpiCells = computed(() => {
  const l = latest.value
  if (!l) return []
  return [
    { key: 'actual',   label: '实测克重', value: fmtN(l.actualWeight),  unit: 'g',  over: !!l.isOver },
    { key: 'set',      label: '设定克重', value: fmtN(l.setWeight),     unit: 'g' },
    { key: 'dev',      label: '偏差',     value: fmtN(l.deviation),      unit: 'g',  over: !!l.isOver },
    { key: 'speed',    label: '线速',     value: fmtN(l.lineSpeed),      unit: 'mm/s' },
    { key: 'roll',     label: '计米长度', value: fmtN(l.rollLength),     unit: 'm' },
    { key: 'out',      label: '计米重量', value: fmtN(l.outputKg),       unit: 'kg' },
    { key: 'yield',    label: '机器产量', value: fmtN(l.machineYield),   unit: 'kg/l' },
    { key: 'fix',      label: '克重修正', value: fmtN(l.gaugeFix),       unit: '' }
  ]
})

// 迷你趋势: 最近 40 条 (实测米重), 用于 App 端快速看波动
const trend = computed(() => curve.value.slice(-40))

// 趋势条高度: 相对本组最大值归一化 (min 6% 保证可见)
function barHeight(t) {
  const vals = trend.value.map(x => Number(x.actualWeight)).filter(v => !isNaN(v))
  if (!vals.length) return '6%'
  const max = Math.max.apply(null, vals)
  const min = Math.min.apply(null, vals)
  const span = max - min
  const v = Number(t.actualWeight)
  if (isNaN(v) || span <= 0) return '20%'
  const ratio = (v - min) / span
  return Math.max(6, Math.round(ratio * 100)) + '%'
}

// ---- 加载 ----
async function loadDevices() {
  loadingDev.value = true
  try {
    const r = await api.weightDevicePage({ pageNum: 1, pageSize: 200 })
    const list = (r && (r.records || r.data)) || []
    devices.value = Array.isArray(list) ? list : []
    if (!deviceId.value && devices.value.length) {
      // 默认选第一个在线的, 没有就第一个
      const online = devices.value.find(d => d.onlineStatus === 'ONLINE')
      deviceId.value = (online || devices.value[0]).id
      loadAll()
    }
  } catch (e) {
    devices.value = []
  } finally {
    loadingDev.value = false
  }
}

async function loadCurve() {
  if (!deviceId.value) return
  try {
    const r = await api.weightCurve({ deviceId: deviceId.value, limit: 200 })
    curve.value = Array.isArray(r) ? r : []
    lastRefresh.value = new Date()
  } catch (e) {
    curve.value = []
  }
}

async function loadSamples() {
  if (!deviceId.value) return
  loadingSamples.value = true
  try {
    const r = await api.weightSamplePage({
      pageNum: pageNum.value, pageSize: pageSize.value, deviceId: deviceId.value
    })
    const recs = (r && (r.records || r.data)) || []
    samples.value = Array.isArray(recs) ? recs : []
    total.value = (r && r.total) || 0
  } catch (e) {
    samples.value = []
    total.value = 0
  } finally {
    loadingSamples.value = false
  }
}

async function loadStats() {
  if (!deviceId.value) { stats.value = []; return }
  try {
    const r = await api.weightStats({ deviceId: deviceId.value })
    stats.value = Array.isArray(r) ? r : []
  } catch (e) {
    stats.value = []
  }
}

async function loadChannels() {
  if (!deviceId.value) { channels.value = []; return }
  try {
    const r = await api.weightChannelList(deviceId.value)
    channels.value = Array.isArray(r) ? r : []
  } catch (e) {
    channels.value = []
  }
}

function loadAll() {
  loadCurve()
  loadSamples()
  loadStats()
  loadChannels()
}

function onPickDevice(d) {
  if (deviceId.value === d.id) return
  deviceId.value = d.id
  pageNum.value = 1
  loadAll()
}

function gotoPage(p) {
  // ⚠️ R20: 必须显式接参 + 同值短路, 漏接参会静默失效
  if (p === pageNum.value || p < 1) return
  pageNum.value = p
  loadSamples()
}

// 30s 轮询曲线 (App 端省流量, 比 PC 的 5s 宽松)
function startTimer() {
  stopTimer()
  timer = setInterval(loadCurve, 30000)
}
function stopTimer() {
  if (timer) { clearInterval(timer); timer = null }
}

onMounted(() => {
  if (!guardPage('production')) return
  loadDevices()
  startTimer()
})

onUnmounted(() => {
  stopTimer()
})
</script>

<style scoped>
.picker-card { padding-bottom: 10px; }
.picker-title { font-size: 14px; font-weight: 600; color: var(--text); margin-bottom: 8px; }
.dev-scroll { white-space: nowrap; }
.dev-chips { display: inline-flex; gap: 8px; padding-bottom: 4px; }
.chip {
  padding: 6px 14px; border-radius: 999px; background: #f0f3f7; color: #5b6774;
  font-size: 13px; white-space: nowrap; border: 1px solid #e3e8ee;
}
.chip-active { background: var(--primary); color: #fff; border-color: var(--primary); }
.dev-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.meta-name { font-size: 13px; font-weight: 600; color: var(--text); }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot-on { background: var(--success); }
.dot-off { background: #b9c2cc; }

.card-head-row { margin-bottom: 10px; }
.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.kpi-cell {
  background: #f7fafc; border: 1px solid #eaf0f6; border-radius: 8px;
  padding: 10px 4px; text-align: center;
}
.kpi-val { font-size: 16px; font-weight: 700; color: var(--primary); line-height: 1.2; }
.kpi-unit { font-size: 10px; color: var(--muted); margin-top: 1px; }
.kpi-lbl { font-size: 11px; color: var(--muted); margin-top: 3px; }
.val-ok { color: var(--success); font-weight: 700; }
.val-bad { color: var(--danger); font-weight: 700; }

.trend-wrap { margin-top: 12px; }
.trend-bars {
  display: flex; align-items: flex-end; gap: 2px; height: 56px;
  background: #f7fafc; border: 1px solid #eaf0f6; border-radius: 8px; padding: 6px;
  box-sizing: border-box; overflow: hidden;
}
.trend-bar { flex: 1; min-width: 2px; border-radius: 1px 1px 0 0; }
.bar-ok { background: var(--success); }
.bar-bad { background: var(--warning); }
.trend-foot { margin-top: 6px; text-align: center; }

.stat-row { padding: 8px 0; border-bottom: 1px solid #f0f2f5; }
.stat-row:last-child { border-bottom: 0; }
.stat-line { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.stat-name { font-size: 13px; font-weight: 600; color: var(--text); }

.sample-item { padding: 10px 0; border-bottom: 1px solid #f0f2f5; }
.sample-item:last-child { border-bottom: 0; }
.sample-time { font-size: 12px; color: #5b6774; }
.sample-w { font-size: 16px; font-weight: 700; }
.sample-grid {
  display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px 4px; margin-top: 8px;
}
.sg-item { display: flex; flex-direction: column; align-items: center; }
.sg-lbl { font-size: 10px; color: var(--muted); }
.sg-val { font-size: 12px; font-weight: 600; color: var(--text); }
.sample-foot { margin-top: 6px; }

.pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 12px; }
.btn-mini {
  background: var(--primary); color: #fff; font-size: 12px; padding: 5px 12px;
  border-radius: 5px; border: 0; line-height: 1.4;
}
.btn-mini[disabled] { background: #c3ccd6; }
.empty { text-align: center; color: var(--muted); font-size: 13px; padding: 24px 0; }
</style>
