<template>
  <view class="container">
    <!-- 顶部: 选车间 -->
    <view class="card">
      <view class="muted form-hint">按车间查设备 (留空=全公司)</view>
      <view class="form-row">
        <text class="form-label">车间</text>
        <input class="form-input" v-model="workshop" placeholder="留空=全部"
          confirm-type="search" @confirm="onQuery" />
      </view>
      <button class="btn-primary" @click="onQuery">查询设备</button>
    </view>

    <!-- 列表 -->
    <view v-if="loading" class="empty muted">加载中...</view>
    <view v-else-if="!items.length" class="empty muted">暂无设备</view>
    <template v-else>
      <view class="card eq-card" v-for="eq in items" :key="eq.id">
        <view class="eq-top">
          <view class="eq-code">{{ eq.equipmentCode || eq.equipment_code || '—' }}</view>
          <view :class="['tag', stKey(eq.status)]">{{ stLabel(eq.status) }}</view>
        </view>
        <view class="eq-name">{{ eq.equipmentName || eq.equipment_name || '—' }}</view>
        <view class="eq-meta muted">
          {{ eq.equipmentType || eq.equipment_type || '—' }}
          <template v-if="eq.workshopName || eq.workshop"> · {{ eq.workshopName || eq.workshop }}</template>
          <template v-if="eq.nextMaintDate || eq.next_maint_date"> · 下次维保 {{ eq.nextMaintDate || eq.next_maint_date }}</template>
        </view>

        <!-- 维保历史 -->
        <view v-if="expanded === eq.id">
          <view class="sub-title">最近维保 (5 条)</view>
          <view v-if="!history[eq.id].length" class="muted empty-sm">暂无维保记录</view>
          <view class="his-row" v-for="h in history[eq.id]" :key="h.id">
            <view class="his-title">{{ h.maintContent || h.sourceType || '维保' }}</view>
            <view class="muted">{{ h.orderNo || '' }} · {{ h.planDate || h.createTime || '' }} · {{ h.status || '' }}</view>
            <view v-if="h.description" class="muted">{{ h.description }}</view>
          </view>
        </view>

        <view class="eq-actions">
          <button class="btn-plain" @click="toggle(eq.id)">
            {{ expanded === eq.id ? '收起' : '维保历史' }}
          </button>
          <button class="btn-primary" :disabled="repairing" @click="onRepair(eq)">报修</button>
        </view>
      </view>
    </template>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import api from '../../api/index.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const workshop = ref('')
const items = ref([])
const expanded = ref(null)
const loading = ref(false)
const repairing = ref(false)
const history = reactive({}) // { equipmentId: [] }

function stLabel(s) {
  if (s == null) return '未知'
  const m = { RUN: '运行', IDLE: '空闲', MAINTAIN: '维保', REPAIR: '维修', SCRAP: '报废', RUNNING: '运行', OFFLINE: '停用' }
  return m[s] || s
}
function stKey(s) {
  if (s == null) return 'tag-info'
  const m = { RUN: 'tag-ok', RUNNING: 'tag-ok', IDLE: 'tag-info', OFFLINE: 'tag-info', MAINTAIN: 'tag-warn', REPAIR: 'tag-danger', SCRAP: 'tag-danger' }
  return m[s] || 'tag-info'
}

function pickArr(r) {
  return (r && (r.records || r.data || r)) || []
}

async function onQuery() {
  loading.value = true
  items.value = []
  expanded.value = null
  try {
    const params = { pageNum: 1, pageSize: 200 }
    const ws = (workshop.value || '').trim()
    if (ws) params.workshop = ws
    const r = await api.equipmentPage(params)
    items.value = pickArr(r)
    if (!Array.isArray(items.value)) items.value = []
  } catch (e) {
    items.value = []
    console.warn('[equipment.onQuery] 失败:', e)
    if (e && e.code === 401) return
    uni.showToast({ title: (e && e.msg) || '查询失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function loadHistory(eq) {
  if (history[eq.id] && history[eq.id].length !== undefined) return
  // v1.1.102 hotfix-4: 维保历史改读新表 em_maintain_order (老表 wf_maintain_record 已废弃, 展开总是空)
  try {
    const r = await api.xkMaintenanceOrders({ pageNum: 1, pageSize: 5, deviceName: eq.equipmentCode || eq.equipment_name })
    let recs = (r && (r.data && r.data.records ? r.data.records : r.data)) || (r && r.records) || []
    // 按当前设备精确过滤 (deviceName 是模糊匹配, 可能带出其他设备)
    const code = eq.equipmentCode || eq.equipment_code
    if (code) recs = recs.filter((x) => !x.equipmentCode || String(x.equipmentCode) === String(code))
    history[eq.id] = Array.isArray(recs) ? recs : []
  } catch (e) {
    history[eq.id] = []
    console.warn('[equipment.loadHistory] 失败:', e)
    if (e && e.code === 401) return
  }
}

function toggle(eq) {
  if (expanded.value === eq.id) {
    expanded.value = null
    return
  }
  expanded.value = eq.id
  loadHistory(eq)
}

async function onRepair(eq) {
  // v1.1.102 hotfix-4: 报修写新表 em_maintain_order (与 XK 设备维保页 /xk/maintenance/orders 同源),
  // 旧 /workflow/maintain 老表数据维保页读不到, 故改走 em 端点. 报修后即跳设备维保页看工单.
  const payload = {
    equipmentId: eq.id,
    equipmentCode: eq.equipmentCode || eq.equipment_code || null,
    equipmentName: eq.equipmentName || eq.equipment_name || null,
    maintContent: '维修',
    sourceType: 'MANUAL',
    status: 'PENDING',
    description: 'App 报修',
    planDate: new Date().toISOString().slice(0, 10)
  }
  repairing.value = true
  try {
    await api.emMaintainOrderAdd(payload)
    uni.showToast({ title: '报修工单已提交', icon: 'success' })
    // 跳 XK 设备维保页看新工单 (该页读 em_maintain_order 新表)
    setTimeout(() => uni.navigateTo({ url: '/pages/xk/maintenance' }), 500)
  } catch (e) {
    console.warn('[equipment.onRepair] 失败:', e)
    if (e && e.code === 401) { uni.showToast({ title: '请先重新登录', icon: 'none' }); repairing.value = false; return }
    uni.showModal({ title: '报修失败', content: (e && e.msg) || '报修失败, 请重试', showCancel: false })
  } finally {
    repairing.value = false
  }
}

onMounted(() => {
  if (!guardPage('em')) {
    uni.showToast({ title: '该模块已停用', icon: 'none' })
    setTimeout(() => uni.switchTab({ url: '/pages/dashboard/index' }), 800)
    return
  }
  onQuery()
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.muted { color:#909399;font-size:12px }
.form-hint { margin-bottom:8px }
.form-row { display:flex;align-items:center;gap:8px;margin-bottom:10px }
.form-label { width:40px;font-size:13px;color:#606266;flex-shrink:0 }
.form-input { flex:1;border:1px solid #dcdfe6;border-radius:5px;padding:8px;font-size:14px }
.btn-primary { background:#1e6091;color:#fff;border:none;border-radius:6px;padding:10px 20px;font-size:14px }
.btn-primary[disabled] { opacity:0.5 }
.btn-plain { background:#f4f4f5;color:#606266;border:none;border-radius:6px;padding:8px 16px;font-size:13px }
.empty { text-align:center;color:#909399;padding:30px;font-size:13px }
.empty-sm { padding:8px 0 }
.eq-card { border-left:3px solid #1e6091 }
.eq-top { display:flex;justify-content:space-between;align-items:center }
.eq-code { font-size:14px;font-weight:700;color:#303133 }
.eq-name { font-size:15px;font-weight:600;margin-top:4px }
.eq-meta { margin-top:4px }
.sub-title { font-size:13px;font-weight:600;margin-top:10px;padding-top:8px;border-top:1px solid #f0f0f0 }
.his-row { padding:8px 0;border-bottom:1px solid #f5f5f5 }
.his-title { font-size:13px;color:#303133;font-weight:500 }
.eq-actions { display:flex;gap:8px;margin-top:10px }
.tag { font-size:11px;padding:2px 8px;border-radius:10px }
.tag-info { background:#f0f2f5;color:#606266 }
.tag-warn { background:#fdf6ec;color:#e6a23c }
.tag-ok { background:#f0f9eb;color:#67c23a }
.tag-danger { background:#fef0f0;color:#f56c6c }
</style>
