<template>
  <div class="page-card">
    <!-- 顶部: 采集器选择 + 工具栏 -->
    <div class="toolbar">
      <el-select v-model="deviceId" placeholder="选择采集器" style="width:220px" @change="onDevice">
        <el-option v-for="d in devices" :key="d.id" :value="d.id"
          :label="`${d.deviceCode}${d.deviceName ? ' · ' + d.deviceName : ''} (${d.onlineStatus || '-'})`" />
      </el-select>
      <el-date-picker v-model="from" type="datetime" placeholder="从" style="width:180px"
        :default-time="new Date(2026, 0, 1, 0, 0, 0)" />
      <el-date-picker v-model="to" type="datetime" placeholder="到" style="width:180px"
        :default-time="new Date(2026, 0, 1, 23, 59, 59)" />
      <el-button v-if="deviceId" @click="loadAll">刷新</el-button>
      <el-tag v-if="!devices.length" type="warning" size="small">
        暂无采集器 — ESP32 上报后自动注册
      </el-tag>
    </div>

    <el-tabs v-model="tab">
      <!-- ================= 实时曲线 ================= -->
      <el-tab-pane label="实时曲线" name="curve">
        <el-card shadow="never" style="margin-top:12px">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <span>米克重曲线 ({{ lastRefresh ? '最后刷新 ' + fmtT(lastRefresh) : '未加载' }})</span>
              <div style="display:flex;gap:8px;align-items:center">
                <el-switch v-model="autoRefresh" @change="onAutoRefresh" active-text="自动" />
                <span v-if="autoRefresh" style="font-size:12px;color:#999">每 5s</span>
              </div>
            </div>
          </template>

          <el-empty v-if="!curve.length" description="暂无采样数据 — ESP32 上报后自动出现"
            style="min-height:280px" />
          <v-chart v-else :option="chartOpt" autoresize style="height:420px" />
        </el-card>
      </el-tab-pane>

      <!-- ================= 采样明细 ================= -->
      <el-tab-pane label="采样明细" name="samples">
        <el-table :data="samples" border stripe v-loading="loadingSamples"
          style="width:100%;margin-top:12px" max-height="480" size="small">
          <el-table-column label="采样时间" width="160" prop="collectTime" />
          <el-table-column label="通道" width="80" prop="channelNo" />
          <el-table-column label="设定" width="90" prop="setWeight" />
          <el-table-column label="实测" width="90">
            <template #default="{ row }">
              <span :class="row.isOver ? 'bad' : 'good'">{{ row.actualWeight }}</span>
            </template>
          </el-table-column>
          <el-table-column label="偏差" width="90" prop="deviation" />
          <el-table-column label="偏差%" width="80" prop="deviationPct" />
          <el-table-column label="线速 mm/s" width="90" prop="lineSpeed" />
          <el-table-column label="计米长度 m" width="100" prop="rollLength" />
          <el-table-column label="计米重量 kg" width="100" prop="outputKg" />
          <el-table-column label="机器产量 kg/l" width="110" prop="machineYield" />
          <el-table-column label="当前重量 kg" width="100" prop="currentWeight" />
          <el-table-column label="上牵引 Hz" width="90" prop="upperHz" />
          <el-table-column label="克重修正" width="90" prop="gaugeFix" />
          <el-table-column label="状态" width="70">
            <template #default="{ row }">
              <el-tag :type="row.isOver ? 'danger' : 'success'" size="small" round>
                {{ row.isOver ? '超差' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="工单" prop="prdOrderNo" min-width="180" show-overflow-tooltip>
            <!-- v1.1.131: 工单号后带商品名称 -->
            <template #default="{ row }">{{ row.prdOrderNo || '-' }}{{ row.productName ? ' · ' + row.productName : '' }}</template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="pageNum" v-model:page-size="pageSize"
            :total="total" :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="onPageChange" @size-change="onSizeChange" />
        </div>
      </el-tab-pane>

      <!-- ================= 统计 ================= -->
      <el-tab-pane label="统计" name="stats">
        <el-table :data="stats" border stripe v-loading="loadingSamples"
          style="width:100%;margin-top:12px" size="small">
          <el-table-column label="通道" width="80" prop="channelNo" />
          <el-table-column label="采样数" prop="sampleCount" />
          <el-table-column label="平均" prop="avgWeight" />
          <el-table-column label="最小" prop="minWeight" />
          <el-table-column label="最大" prop="maxWeight" />
          <el-table-column label="平均偏差" prop="avgDeviation" />
          <el-table-column label="平均偏差%" prop="avgDeviationPct" />
          <el-table-column label="超标次数" prop="overCount" />
          <el-table-column label="超标率%">
            <template #default="{ row }">
              <span :class="Number(row.overRatePct) > 10 ? 'bad' : ''">{{ row.overRatePct }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ================= 通道配置 ================= -->
      <el-tab-pane label="通道配置" name="config">
        <div style="margin-top:12px">
          <el-table :data="channels" border stripe size="small" v-if="channels.length">
            <el-table-column label="通道号" width="70" prop="channelNo" />
            <el-table-column label="名称" width="100" prop="channelName" />
            <el-table-column label="寄存器" width="80" prop="regStart" />
            <el-table-column label="功能码" width="80">
              <template #default="{ row }">{{ row.regType === 3 ? '03' : row.regType === 4 ? '04' : row.regType }}</template>
            </el-table-column>
            <el-table-column label="缩放系数" prop="scale" width="110" />
            <el-table-column label="单位" width="60" prop="unit" />
            <el-table-column label="设定值" prop="setWeight" width="90" />
            <el-table-column label="上公差" prop="upperTol" width="90" />
            <el-table-column label="下公差" prop="lowerTol" width="90" />
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="editChannel(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="该采集器尚未配置通道 — 协议反推后回填寄存器表"
            :image-size="80" style="min-height:200px" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 通道编辑弹窗 -->
    <el-dialog v-model="chDlg" title="通道配置" width="520px" destroy-on-close>
      <el-form :model="chForm" label-width="90px">
        <el-form-item label="通道号">
          <el-input-number :model-value="chForm.channelNo" :disabled="!!chForm.id" />
        </el-form-item>
        <el-form-item label="通道名"><el-input v-model="chForm.channelName" placeholder="称重1" /></el-form-item>
        <el-form-item label="寄存器地址"><el-input-number v-model.number="chForm.regStart" :min="0" /></el-form-item>
        <el-form-item label="功能码">
          <el-select v-model.number="chForm.regType" style="width:100%">
            <el-option :value="3" label="03 保持寄存器" />
            <el-option :value="4" label="04 输入寄存器" />
          </el-select>
        </el-form-item>
        <el-form-item label="缩放系数"><el-input-number v-model="chForm.scale" :precision="6" :step="0.001" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="chForm.unit" style="width:100px" placeholder="g" /></el-form-item>
        <el-form-item label="设定值"><el-input-number v-model.number="chForm.setWeight" :precision="3" /></el-form-item>
        <el-form-item label="上公差"><el-input-number v-model.number="chForm.upperTol" :precision="3" /></el-form-item>
        <el-form-item label="下公差"><el-input-number v-model.number="chForm.lowerTol" :precision="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chDlg = false">取消</el-button>
        <el-button type="primary" @click="saveChannel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { weightApi } from '@/api/production'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, GridComponent,
  LegendComponent, DataZoomComponent, MarkLineComponent
} from 'echarts/components'
import VChart from 'vue-echarts'
use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent,
     GridComponent, LegendComponent, DataZoomComponent, MarkLineComponent])

// ---- 状态 ----
const devices = ref([])
const deviceId = ref(null)
const from = ref(null)
const to = ref(null)

const tab = ref('curve')
const autoRefresh = ref(false)
const curve = ref([])
const stats = ref([])
const channels = ref([])

const loadingSamples = ref(false)
const samples = ref([])
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

const chDlg = ref(false)
const chForm = reactive({
  id: null, channelNo: 1, channelName: '', regStart: 0, regType: 4,
  scale: 1, unit: 'g', setWeight: null, upperTol: null, lowerTol: null
})

let timer = null
const lastRefresh = ref(null)

// ---- 工具 ----
const fmtT = (t) => t ? String(t).replace('T', ' ').slice(0, 19) : '-'

// 通道颜色 (多通道曲线)
const CHANNEL_COLORS = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6C', '#909399']

// ---- 曲线 option: 实测值一条线/通道 + 设定值基准线 + 偏差带 ----
const chartOpt = computed(() => {
  const chans = Array.from(new Set(curve.value.map(r => Number(r.channelNo))))
  const t = (r) => String(r.collectTime || '').slice(0, 16)

  const series = chans.map((c, i) => {
    const pts = curve.value.filter(r => Number(r.channelNo) === c)
    const set = pts.length ? pts[0].setWeight : null
    const data = pts.map(r => [t(r), r.actualWeight])
    const markLine = {}
    if (set != null && set !== '') {
      markLine.data = [{ yAxis: Number(set), name: '设定值' }]
      markLine.label = { formatter: '设定 {c}', position: 'insideEndTop' }
      markLine.lineStyle = { type: 'dashed', color: '#909399' }
    }
    return {
      name: '通道' + c,
      type: 'line',
      smooth: true,
      showSymbol: false,
      data,
      lineStyle: { width: 2, color: CHANNEL_COLORS[i % CHANNEL_COLORS.length] },
      markLine
    }
  })

  return {
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => v != null ? Number(v).toFixed(3) : '—'
    },
    legend: { top: 0, type: 'scroll', data: chans.map(c => '通道' + c) },
    grid: { left: 48, right: 24, top: 36, bottom: 56 },
    dataZoom: [
      { type: 'slider', start: 0, end: 100, height: 18, bottom: 10 },
      { type: 'inside', start: 0, end: 100 }
    ],
    xAxis: { type: 'category', boundaryGap: false, data: curve.value.map(r => t(r)) },
    yAxis: { type: 'value', name: '米克重' },
    series
  }
})

// ---- 加载 ----
async function loadDevices() {
  try {
    const r = await weightApi.devicePage({ pageNum: 1, pageSize: 200 })
    devices.value = r.data?.records || []
    if (!deviceId.value && devices.value.length) {
      // 默认选第一个在线的, 没有就第一个
      deviceId.value = (devices.value.find(d => d.onlineStatus === 'ONLINE') || devices.value[0]).id
      loadAll()
    }
  } catch (e) {
    devices.value = []
  }
}

async function loadCurve() {
  if (!deviceId.value) return
  const params = { deviceId: deviceId.value, limit: 500 }
  if (from.value) params.from = fmtT(from.value)
  if (to.value) params.to = fmtT(to.value)
  try {
    const r = await weightApi.curve(params)
    curve.value = r.data || []
    lastRefresh.value = new Date()
  } catch { curve.value = [] }
}

async function loadSamples() {
  if (!deviceId.value) return
  loadingSamples.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize: pageSize.value, deviceId: deviceId.value }
    if (from.value) params.from = fmtT(from.value)
    if (to.value) params.to = fmtT(to.value)
    const r = await weightApi.samplePage(params)
    samples.value = r.data?.records || []
    total.value = Number(r.data?.total) || 0
  } catch { samples.value = []; total.value = 0 } finally {
    loadingSamples.value = false
  }
}

async function loadStats() {
  if (!deviceId.value) return
  try {
    const params = { deviceId: deviceId.value }
    if (from.value) params.from = fmtT(from.value)
    if (to.value) params.to = fmtT(to.value)
    const r = await weightApi.stats(params)
    stats.value = r.data || []
  } catch { stats.value = [] }
}

async function loadChannels() {
  if (!deviceId.value) { channels.value = []; return }
  try {
    const r = await weightApi.channelList(deviceId.value)
    channels.value = r.data || []
  } catch { channels.value = [] }
}

function loadAll() {
  loadCurve()
  loadSamples()
  loadStats()
  loadChannels()
}

// ---- 事件 ----
function onDevice() {
  loadAll()
}

function onAutoRefresh(val) {
  if (val) {
    startTimer()
  } else {
    stopTimer()
  }
}

function startTimer() {
  stopTimer()
  timer = setInterval(loadCurve, 5000)
}

function stopTimer() {
  if (timer) { clearInterval(timer); timer = null }
}

function onPageChange(p) {
  // ⚠️ R20: handler 必须显式接参 + 同值短路, 漏接参会静默失效
  if (p === pageNum.value) return
  pageNum.value = p
  loadSamples()
}

function onSizeChange(s) {
  pageSize.value = s
  pageNum.value = 1
  loadSamples()
}

// ---- 通道编辑 ----
function editChannel(row) {
  Object.assign(chForm, { ...row, id: row.id, channelNo: row.channelNo })
  chDlg.value = true
}

async function saveChannel() {
  if (!deviceId.value) return
  chForm.deviceId = deviceId.value
  try {
    await weightApi.channelSave({ ...chForm, scale: Number(chForm.scale) })
    chDlg.value = false
    loadChannels()
  } catch { /* 拦截器已弹 */ }
}

// ---- 生命周期 ----
onMounted(() => {
  loadDevices()
  startTimer()
})

onUnmounted(() => {
  stopTimer()
})
</script>

<style scoped>
.toolbar {
  display: flex; gap: 10px; align-items: center;
  margin-bottom: 8px;
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.good { color: #67C23A; font-weight: 600; }
.bad  { color: #F56C6C; font-weight: 600; }
</style>