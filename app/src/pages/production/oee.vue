<template>
  <view class="container">
    <!-- 设备选择 -->
    <view class="card picker-card">
      <view class="picker-title">选择设备</view>
      <scroll-view scroll-x="true" class="eq-scroll" :show-scrollbar="false">
        <view class="eq-chips">
          <view v-for="eq in equipmentList" :key="eq.id"
            :class="['chip', selectedEqId === eq.id ? 'chip-active' : '']"
            @click="onPick(eq)">
            {{ eq.equipmentCode || eq.equipment_code || eq.equipmentName || eq.id }}
          </view>
        </view>
      </scroll-view>
      <view v-if="loadingEq" class="muted">设备加载中...</view>
      <view v-else-if="!equipmentList.length" class="muted">暂无设备</view>
    </view>

    <!-- OEE 卡片 -->
    <view v-if="oee" class="card oee-card">
      <view class="oee-title">{{ oee.equipmentName || oee.equipmentCode || oee.equipmentCode || '' }}</view>
      <view class="oee-grid">
        <view class="oee-cell">
          <view class="oee-value">{{ fmtPct(oee.availability) }}</view>
          <view class="oee-label">稼动率</view>
          <view class="oee-bar"><view class="oee-bar-fill" :style="{ width: oee.availability + '%' }"></view></view>
        </view>
        <view class="oee-cell">
          <view class="oee-value">{{ fmtPct(oee.performance) }}</view>
          <view class="oee-label">性能率</view>
          <view class="oee-bar"><view class="oee-bar-fill" :style="{ width: oee.performance + '%' }"></view></view>
        </view>
        <view class="oee-cell">
          <view class="oee-value">{{ fmtPct(oee.quality) }}</view>
          <view class="oee-label">良品率</view>
          <view class="oee-bar"><view class="oee-bar-fill" :style="{ width: oee.quality + '%' }"></view></view>
        </view>
        <view class="oee-cell oee-total">
          <view class="oee-value oee-total-val">{{ fmtPct(oee.oee) }}</view>
          <view class="oee-label">OEE 综合</view>
        </view>
      </view>
      <view class="oee-stats">
        <view class="oee-stat">
          <view class="oee-stat-val">{{ fmtMin(oee.planMinutes) }}</view>
          <view class="oee-label">计划工时</view>
        </view>
        <view class="oee-stat">
          <view class="oee-stat-val">{{ fmtMin(oee.activeMinutes) }}</view>
          <view class="oee-label">实际稼动</view>
        </view>
        <view class="oee-stat">
          <view class="oee-stat-val">{{ fmtMin(oee.downtimeMinutes) }}</view>
          <view class="oee-label">停机时间</view>
        </view>
        <view class="oee-stat">
          <view class="oee-stat-val">{{ oee.records || 0 }}</view>
          <view class="oee-label">耗时记录</view>
        </view>
      </view>
    </view>

    <!-- 该设备近期排程 -->
    <view v-if="selectedEqId" class="card">
      <view class="card-title">该设备排程 (近 7 条)</view>
      <view v-if="loadingSched" class="muted">加载中...</view>
      <view v-else-if="!schedules.length" class="muted">暂无排程</view>
      <view v-for="s in schedules" :key="s.id" class="sched-row">
        <view class="sched-head">
          <!-- v1.1.131: 工单号后带商品名称 (后端 MesSchedule.productName 注入) -->
          <text class="sched-order">{{ s.prdOrderNo || '—' }}<text v-if="s.productName" class="sched-pname"> · {{ s.productName }}</text></text>
          <text :class="['tag', 'tag-' + s.status]">{{ s.status }}</text>
        </view>
        <view class="muted">工序 #{{ s.processNo || '-' }} {{ s.processName || '' }} ·
          {{ s.planStart || '' }} ~ {{ s.planEnd || '' }}</view>
      </view>
    </view>

    <view v-if="selectedEqId && oee" class="card">
      <view class="card-title">数据说明</view>
      <view class="muted">
        OEE = 稼动率 × 性能率 × 良品率。稼动率来自 mes_process_time 实际工时 ÷ 排程计划工时；
        性能率/良品率暂取 100%（后续可接 prd_process 报工良率细化）。
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import api from '../../api/index.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const equipmentList = ref([])
const loadingEq = ref(false)
const selectedEqId = ref(null)
const oee = ref(null)
const schedules = ref([])
const loadingSched = ref(false)

function fmtPct(v) {
  const n = Number(v)
  if (isNaN(n)) return '—'
  return n.toFixed(1) + '%'
}
function fmtMin(min) {
  const n = Number(min || 0)
  const h = Math.floor(n / 60)
  const m = n % 60
  return h ? h + 'h' + (m ? m + 'm' : '') : m + 'm'
}

async function loadEquipment() {
  loadingEq.value = true
  try {
    const r = await api.equipmentPage({ pageNum: 1, pageSize: 100 })
    equipmentList.value = (r && (r.records || r.data)) || []
    if (!Array.isArray(equipmentList.value)) equipmentList.value = []
    // 默认选第一个
    if (equipmentList.value.length && !selectedEqId.value) {
      onPick(equipmentList.value[0])
    }
  } catch (e) {
    equipmentList.value = []
    console.warn('[oee.loadEquipment] 失败:', e)
    if (e && e.code === 401) return
    uni.showToast({ title: '设备加载失败', icon: 'none' })
  } finally {
    loadingEq.value = false
  }
}

async function onPick(eq) {
  selectedEqId.value = eq.id
  oee.value = null
  schedules.value = []
  // OEE
  try {
    const r = await api.oee(eq.id)
    oee.value = r || null
  } catch (e) {
    oee.value = null
    console.warn('[oee.loadOee] 失败:', e)
  }
  // 排程近 7 条
  loadingSched.value = true
  try {
    const r = await api.schedulePage({ equipmentId: eq.id, pageNum: 1, pageSize: 7 })
    schedules.value = (r && (r.records || r.data)) || []
    if (!Array.isArray(schedules.value)) schedules.value = []
  } catch (e) {
    schedules.value = []
    console.warn('[oee.loadSched] 失败:', e)
  } finally {
    loadingSched.value = false
  }
}

onMounted(() => {
  if (!guardPage('production')) {
    uni.showToast({ title: '该模块已停用', icon: 'none' })
    setTimeout(() => uni.switchTab({ url: '/pages/dashboard/index' }), 800)
    return
  }
  loadEquipment()
})
</script>

<style scoped>
.container { padding: 12px; }
.muted { color:#909399;font-size:12px }
.picker-card { border-left:3px solid #1e6091 }
.picker-title { font-size:13px;font-weight:600;margin-bottom:8px }
.eq-scroll { white-space:nowrap }
.eq-chips { display:inline-flex;gap:8px;padding-bottom:4px }
.chip { display:inline-block;padding:6px 12px;border-radius:16px;font-size:13px;background:#f0f2f5;color:#606266 }
.chip-active { background:#1e6091;color:#fff }
.oee-card { border-left:3px solid #67c23a }
.oee-title { font-size:16px;font-weight:700;margin-bottom:12px }
.oee-grid { display:grid;grid-template-columns:1fr 1fr;gap:12px }
.oee-cell { text-align:center;padding:10px;background:#f8f9fb;border-radius:6px }
.oee-value { font-size:22px;font-weight:800;color:#1e6091 }
.oee-label { font-size:11px;color:#909399;margin-top:4px }
.oee-bar { height:4px;background:#e4e7ed;border-radius:2px;margin-top:6px;overflow:hidden }
.oee-bar-fill { height:100%;background:#1e6091 }
.oee-total { background:#e8f5e9 }
.oee-total-val { color:#67c23a }
.oee-stats { display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:14px;padding-top:14px;border-top:1px solid #f0f0f0 }
.oee-stat { text-align:center }
.oee-stat-val { font-size:18px;font-weight:700;color:#303133 }
.card-title { font-size:14px;font-weight:600;margin-bottom:8px;color:#303133 }
.sched-row { padding:8px 0;border-bottom:1px solid #f5f5f5 }
.sched-head { display:flex;justify-content:space-between;align-items:center }
.sched-order { font-weight:600;font-size:13px }
.sched-pname { font-weight:400;font-size:12px;color:#909399 }
.tag { font-size:11px;padding:2px 8px;border-radius:10px }
.tag-PLANNED { background:#f0f2f5;color:#606266 }
.tag-IN_PROGRESS { background:#fdf6ec;color:#e6a23c }
.tag-DONE { background:#f0f9eb;color:#67c23a }
.tag-CANCELLED { background:#fef0f0;color:#f56c6c }
</style>
