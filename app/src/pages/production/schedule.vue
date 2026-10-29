<template>
  <view class="container">
    <!-- 筛选 -->
    <view class="card">
      <view class="form-row">
        <text class="form-label">工单号</text>
        <input class="form-input" v-model="filterOrderNo" placeholder="留空=全部" />
      </view>
      <view class="form-row">
        <text class="form-label">状态</text>
        <view class="status-chips">
          <view v-for="s in STATUS_OPTIONS" :key="s.value"
            :class="['chip', filterStatus === s.value ? 'chip-active' : '']"
            @click="filterStatus = s.value; loadData()">
            {{ s.label }}
          </view>
        </view>
      </view>
      <button class="btn-primary" :disabled="loading" @click="loadData">查询排程</button>
    </view>

    <!-- 列表 -->
    <view v-if="loading" class="empty muted">加载中...</view>
    <view v-else-if="!list.length" class="empty muted">暂无排程记录</view>
    <template v-else>
      <view class="card sched-card" v-for="s in list" :key="s.id">
        <view class="sched-head">
          <!-- v1.1.131: 工单号后带商品名称 (后端 MesSchedule.productName 注入) -->
          <text class="sched-order">{{ s.prdOrderNo || '—' }}<text v-if="s.productName" class="sched-pname"> · {{ s.productName }}</text></text>
          <text :class="['tag', 'tag-' + s.status]">{{ s.statusLabel || s.status }}</text>
        </view>
        <view class="sched-eq">
          🏭 {{ s.equipmentName || s.equipmentCode || '—' }}
          <text class="muted"> · 工序 #{{ s.processNo || '-' }} {{ s.processName || '' }}</text>
        </view>
        <view class="sched-time muted">
          计划 {{ s.planStart || '' }} ~ {{ s.planEnd || '' }}
          <template v-if="s.actualStart"> · 实际 {{ s.actualStart }} ~ {{ s.actualEnd || '进行中' }}</template>
        </view>
        <view v-if="s.oee != null" class="sched-oee">OEE {{ Number(s.oee).toFixed(1) }}%</view>
        <view class="sched-actions">
          <button v-if="s.status !== 'CANCELLED'" class="btn-plain" @click="onCancel(s)">取消</button>
        </view>
      </view>
      <!-- 分页 -->
      <view class="pager">
        <button class="btn-plain" :disabled="pageNum <= 1 || loading" @click="prevPage">上一页</button>
        <text class="muted">第 {{ pageNum }} / {{ totalPages || 1 }} 页</text>
        <button class="btn-plain" :disabled="pageNum >= totalPages || loading" @click="nextPage">下一页</button>
      </view>
    </template>

    <!-- 新建排程 (FAB) -->
    <view class="fab" @click="showAdd = true">＋</view>

    <!-- 新建弹窗 -->
    <view v-if="showAdd" class="mask" @click.self="showAdd = false">
      <view class="dialog">
        <view class="dialog-title">新建排程</view>
        <view class="form-row">
          <text class="form-label">设备</text>
          <picker :range="eqOptions" range-key="equipmentCode" @change="onPickEq">
            <view class="form-input picker">{{ addForm.equipmentName || '请选择设备' }}</view>
          </picker>
        </view>
        <view class="form-row">
          <text class="form-label">工单</text>
          <input class="form-input" v-model="addForm.prdOrderNo" placeholder="PD..." />
        </view>
        <view class="form-row">
          <text class="form-label">工序号</text>
          <input class="form-input" v-model="addForm.processNo" type="number" placeholder="1" />
        </view>
        <view class="form-row">
          <text class="form-label">工序名</text>
          <input class="form-input" v-model="addForm.processName" placeholder="如 吹膜" />
        </view>
        <view class="form-row">
          <text class="form-label">开工</text>
          <input class="form-input" v-model="addForm.planStart" placeholder="2026-10-17 08:00" />
        </view>
        <view class="form-row">
          <text class="form-label">完工</text>
          <input class="form-input" v-model="addForm.planEnd" placeholder="2026-10-17 16:00" />
        </view>
        <view class="dialog-actions">
          <button class="btn-plain" @click="showAdd = false">取消</button>
          <button class="btn-primary" :disabled="submitting" @click="onSubmit">保存</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import api from '../../api/index.js'
import { guardPage } from '../../utils/appModuleEnabled.js'

const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '待排', value: 'PLANNED' },
  { label: '进行中', value: 'IN_PROGRESS' },
  { label: '完成', value: 'DONE' },
  { label: '取消', value: 'CANCELLED' }
]
const list = ref([])
const loading = ref(false)
const pageNum = ref(1)
const pageSize = 10
const totalPages = ref(0)
const filterOrderNo = ref('')
const filterStatus = ref('')
const showAdd = ref(false)
const submitting = ref(false)
const eqOptions = ref([])
const addForm = reactive({ equipmentId: null, equipmentCode: '', equipmentName: '', prdOrderNo: '', processNo: 1, processName: '', planStart: '', planEnd: '' })

async function loadData() {
  loading.value = true
  try {
    const params = { pageNum: pageNum.value, pageSize, equipmentId: undefined, prdOrderId: undefined, status: filterStatus.value }
    const r = await api.schedulePage(params)
    const recs = (r && r.records) || []
    list.value = recs
    const total = (r && r.total) || 0
    totalPages.value = Math.ceil(total / pageSize)
  } catch (e) {
    console.warn('[schedule.load] 失败:', e)
    if (e && e.code === 401) return
    uni.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function prevPage() { if (pageNum.value > 1) { pageNum.value--; loadData() } }
function nextPage() { if (pageNum.value < totalPages.value) { pageNum.value++; loadData() } }

async function loadEqOptions() {
  try {
    const r = await api.equipmentPage({ pageNum: 1, pageSize: 100 })
    eqOptions.value = (r && (r.records || r.data)) || []
    if (!Array.isArray(eqOptions.value)) eqOptions.value = []
  } catch (e) { eqOptions.value = [] }
}

function onPickEq(e) {
  const i = e.detail.value
  const eq = eqOptions.value[i]
  if (eq) {
    addForm.equipmentId = eq.id
    addForm.equipmentCode = eq.equipmentCode
    addForm.equipmentName = eq.equipmentName
  }
}

async function onCancel(s) {
  uni.showModal({
    title: '确认取消',
    content: '取消该排程 ' + (s.prdOrderNo || s.id) + '?',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await api.scheduleCancel(s.id)
        uni.showToast({ title: '已取消', icon: 'success' })
        loadData()
      } catch (e) {
        uni.showToast({ title: (e && e.msg) || '取消失败', icon: 'none' })
      }
    }
  })
}

async function onSubmit() {
  if (!addForm.equipmentId) { uni.showToast({ title: '请选择设备', icon: 'none' }); return }
  submitting.value = true
  try {
    const payload = {
      prdOrderNo: addForm.prdOrderNo,
      equipmentId: addForm.equipmentId,
      equipmentCode: addForm.equipmentCode,
      equipmentName: addForm.equipmentName,
      processNo: Number(addForm.processNo) || 1,
      processName: addForm.processName,
      planStart: addForm.planStart,
      planEnd: addForm.planEnd,
      status: 'PLANNED'
    }
    await api.scheduleSave(payload)
    uni.showToast({ title: '排程已创建', icon: 'success' })
    showAdd.value = false
    pageNum.value = 1
    loadData()
  } catch (e) {
    uni.showToast({ title: (e && e.msg) || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (!guardPage('production')) {
    uni.showToast({ title: '该模块已停用', icon: 'none' })
    setTimeout(() => uni.switchTab({ url: '/pages/dashboard/index' }), 800)
    return
  }
  loadData()
  loadEqOptions()
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 60px; }
.muted { color:#909399;font-size:12px }
.form-row { display:flex;align-items:center;gap:8px;margin-bottom:10px }
.form-label { width:44px;font-size:13px;color:#606266;flex-shrink:0 }
.form-input { flex:1;border:1px solid #dcdfe6;border-radius:5px;padding:8px;font-size:14px }
.btn-primary { background:#1e6091;color:#fff;border:none;border-radius:6px;padding:10px 20px;font-size:14px }
.btn-primary[disabled] { opacity:0.5 }
.btn-plain { background:#f0f2f5;color:#606266;border:none;border-radius:6px;padding:8px 16px;font-size:13px }
.empty { text-align:center;color:#909399;padding:30px;font-size:13px }
.status-chips { display:flex;gap:6px;flex-wrap:wrap }
.chip { padding:5px 10px;border-radius:14px;font-size:12px;background:#f0f2f5;color:#606266 }
.chip-active { background:#1e6091;color:#fff }
.sched-card { border-left:3px solid #1e6091 }
.sched-head { display:flex;justify-content:space-between;align-items:center;margin-bottom:4px }
.sched-order { font-weight:700;font-size:14px }
.sched-pname { font-weight:400;font-size:12px;color:#909399 }
.sched-eq { font-size:13px;margin:4px 0 }
.sched-time { margin:4px 0 }
.sched-oee { font-size:12px;color:#67c23a;font-weight:600;margin-top:4px }
.sched-actions { display:flex;gap:8px;margin-top:8px }
.tag { font-size:11px;padding:2px 8px;border-radius:10px }
.tag-PLANNED { background:#f0f2f5;color:#606266 }
.tag-IN_PROGRESS { background:#fdf6ec;color:#e6a23c }
.tag-DONE { background:#f0f9eb;color:#67c23a }
.tag-CANCELLED { background:#fef0f0;color:#f56c6c }
.pager { display:flex;align-items:center;justify-content:center;gap:12px;padding:10px 0 }
.fab { position:fixed;bottom:24px;right:24px;width:52px;height:52px;border-radius:50%;background:#1e6091;color:#fff;font-size:28px;display:flex;align-items:center;justify-content:center;box-shadow:0 4px 12px rgba(0,0,0,.2);z-index:10 }
.mask { position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:center;justify-content:center;z-index:100 }
.dialog { background:#fff;border-radius:10px;width:86%;max-height:80vh;overflow-y:auto;padding:18px }
.dialog-title { font-size:16px;font-weight:700;margin-bottom:14px }
.picker { color:#303133 }
.dialog-actions { display:flex;gap:10px;margin-top:14px }
.dialog-actions button { flex:1 }
</style>
