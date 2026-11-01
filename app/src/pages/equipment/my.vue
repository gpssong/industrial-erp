<template>
  <view class="container">
    <!-- 顶部: 选车间 + 新增 -->
    <view class="card">
      <view class="muted form-hint">按车间查设备 (留空=全公司)</view>
      <view class="form-row">
        <text class="form-label">车间</text>
        <input class="form-input" v-model="workshop" placeholder="留空=全部"
          confirm-type="search" @confirm="onQuery" />
      </view>
      <view class="btn-row">
        <button class="btn-primary flex1" @click="onQuery">查询设备</button>
        <button v-if="canAdd" class="btn-primary flex1" @click="openAdd">＋ 新增设备</button>
      </view>
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
          {{ typeLabel(eq.equipmentType || eq.equipment_type) }}
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

    <!-- 新增设备 (全屏底部浮层) -->
    <view v-if="showAdd" class="sheet-mask" @click="closeAdd">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-title">新增设备</text>
          <text class="sheet-close" @click="closeAdd">关闭</text>
        </view>
        <view class="sheet-body">
          <view class="form-item">
            <text class="label">设备编码 *</text>
            <input class="form-input" v-model="form.equipmentCode" placeholder="如 EQ001" />
          </view>
          <view class="form-item">
            <text class="label">设备名称 *</text>
            <input class="form-input" v-model="form.equipmentName" placeholder="如 1号吹膜机" />
          </view>
          <view class="form-item">
            <text class="label">设备类型 *</text>
            <picker mode="selector" :range="typeLabels" :value="typeIndex" @change="onPickType">
              <view :class="['form-input', 'picker', form.equipmentType ? '' : 'placeholder-text']">
                {{ form.equipmentType ? typeLabel(form.equipmentType) : '请选择设备类型' }}
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="label">车间</text>
            <input class="form-input" v-model="form.workshop" placeholder="如 吹膜车间" />
          </view>
          <view class="form-item">
            <text class="label">规格型号</text>
            <input class="form-input" v-model="form.model" placeholder="如 SJ-65" />
          </view>
          <view class="form-item">
            <text class="label">生产厂家</text>
            <input class="form-input" v-model="form.manufacturer" placeholder="选填" />
          </view>
          <view class="form-item">
            <text class="label">安装位置</text>
            <input class="form-input" v-model="form.location" placeholder="如 一号厂房东侧" />
          </view>
          <view class="form-item">
            <text class="label">安装日期</text>
            <picker mode="date" :value="form.installDate || today" @change="onPickInstallDate">
              <view :class="['form-input', 'picker', form.installDate ? '' : 'placeholder-text']">
                {{ form.installDate || '请选择安装日期' }}
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="label">设备状态</text>
            <picker mode="selector" :range="statusLabels" :value="statusIndex" @change="onPickStatus">
              <view class="form-input picker">{{ statusLabel(form.status) }}</view>
            </picker>
          </view>
          <view class="form-item">
            <text class="label">责任人</text>
            <input class="form-input" v-model="form.owner" placeholder="选填" />
          </view>
          <view class="form-item">
            <text class="label">下次维保</text>
            <picker mode="date" :value="form.nextMaintDate || today" @change="onPickNextMaintDate">
              <view :class="['form-input', 'picker', form.nextMaintDate ? '' : 'placeholder-text']">
                {{ form.nextMaintDate || '请选择下次维保日期' }}
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="label">备注</text>
            <textarea class="form-textarea" v-model="form.remark" maxlength="200"
              placeholder="选填, 最多 200 字" />
          </view>
        </view>
        <view class="sheet-foot">
          <button class="btn-plain flex1" @click="closeAdd">取消</button>
          <button class="btn-primary flex1" :disabled="saving" @click="onSubmit">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import api from '../../api/index.js'
import { guardPage, isPermEnabled } from '../../utils/appModuleEnabled.js'
import { isAdmin, getAppPermissions } from '../../utils/permission.js'

// 与 PC 设备主数据页 / 后端 Constants.EQUIPMENT_TYPE_* 同源
const TYPE_OPTIONS = [
  { value: 'FILM_BLOWING', label: '吹膜' },
  { value: 'SIDE_SEAL', label: '侧封' },
  { value: 'BAG_MAKING', label: '制袋' },
  { value: 'PRINTING', label: '印刷' },
  { value: 'LAMINATING', label: '复合' },
  { value: 'SLITTING', label: '分切' }
]
const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '在用' },
  { value: 'MAINT', label: '维保中' },
  { value: 'IDLE', label: '闲置' },
  { value: 'SCRAP', label: '报废' }
]
const typeLabels = TYPE_OPTIONS.map(t => t.label)
const statusLabels = STATUS_OPTIONS.map(s => s.label)

const workshop = ref('')
const items = ref([])
const expanded = ref(null)
const loading = ref(false)
const repairing = ref(false)
const history = reactive({}) // { equipmentId: [] }

const canAdd = ref(false)
const showAdd = ref(false)
const saving = ref(false)
const form = reactive(emptyForm())

const typeIndex = computed(() => Math.max(0, TYPE_OPTIONS.findIndex(t => t.value === form.equipmentType)))
const statusIndex = computed(() => Math.max(0, STATUS_OPTIONS.findIndex(s => s.value === form.status)))
const today = todayStr()

function todayStr() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function emptyForm() {
  return {
    equipmentCode: '', equipmentName: '', equipmentType: '', workshop: '',
    model: '', manufacturer: '', location: '', installDate: '',
    status: 'ACTIVE', owner: '', nextMaintDate: '', remark: ''
  }
}

function typeLabel(t) {
  if (!t) return '—'
  const f = TYPE_OPTIONS.find(x => x.value === t)
  return f ? f.label : t
}
function statusLabel(s) {
  const f = STATUS_OPTIONS.find(x => x.value === s)
  return f ? f.label : (s || '在用')
}
function stLabel(s) {
  if (s == null) return '未知'
  const m = { ACTIVE: '在用', MAINT: '维保中', IDLE: '闲置', SCRAP: '报废', RUN: '运行', RUNNING: '运行', REPAIR: '维修', MAINTAIN: '维保', OFFLINE: '停用' }
  return m[s] || s
}
function stKey(s) {
  if (s == null) return 'tag-info'
  const m = { ACTIVE: 'tag-ok', RUN: 'tag-ok', RUNNING: 'tag-ok', MAINT: 'tag-warn', MAINTAIN: 'tag-warn', REPAIR: 'tag-danger', SCRAP: 'tag-danger', IDLE: 'tag-info', OFFLINE: 'tag-info' }
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

function openAdd() {
  if (!canAdd.value) return
  Object.assign(form, emptyForm())
  form.workshop = (workshop.value || '').trim()
  showAdd.value = true
}
function closeAdd() {
  if (saving.value) return
  showAdd.value = false
}
function onPickType(e) {
  form.equipmentType = TYPE_OPTIONS[Number(e.detail.value)].value
}
function onPickStatus(e) {
  form.status = STATUS_OPTIONS[Number(e.detail.value)].value
}
function onPickInstallDate(e) {
  form.installDate = e.detail.value
}
function onPickNextMaintDate(e) {
  form.nextMaintDate = e.detail.value
}

async function onSubmit() {
  const code = (form.equipmentCode || '').trim()
  const name = (form.equipmentName || '').trim()
  if (!code) { uni.showToast({ title: '请输入设备编码', icon: 'none' }); return }
  if (!name) { uni.showToast({ title: '请输入设备名称', icon: 'none' }); return }
  if (!form.equipmentType) { uni.showToast({ title: '请选择设备类型', icon: 'none' }); return }
  if (items.value.some(x => String(x.equipmentCode || '').trim() === code)) {
    uni.showToast({ title: '设备编码已存在', icon: 'none' })
    return
  }
  const payload = {
    equipmentCode: code,
    equipmentName: name,
    equipmentType: form.equipmentType,
    status: form.status || 'ACTIVE',
    workshop: (form.workshop || '').trim(),
    model: (form.model || '').trim(),
    manufacturer: (form.manufacturer || '').trim(),
    location: (form.location || '').trim(),
    owner: (form.owner || '').trim(),
    remark: (form.remark || '').trim(),
    installDate: form.installDate || null,
    nextMaintDate: form.nextMaintDate || null
  }
  saving.value = true
  try {
    await api.equipmentAdd(payload)
    uni.showToast({ title: '设备已新增', icon: 'success' })
    showAdd.value = false
    Object.assign(form, emptyForm())
    // 车间筛选与新设备车间不同 → 清空筛选, 否则刚新增的设备看不到
    const wsFilter = (workshop.value || '').trim()
    if (wsFilter && wsFilter !== payload.workshop) workshop.value = ''
    await onQuery()
  } catch (e) {
    console.warn('[equipment.onSubmit] 失败:', e)
    if (e && e.code === 401) return
    uni.showModal({ title: '新增失败', content: (e && e.msg) || '新增失败, 请重试', showCancel: false })
  } finally {
    saving.value = false
  }
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
  // 新增入口按 APP 端 perm 显隐 (getAppPermissions 从 erp_menus 派生, 与 PC 授权项一致)
  canAdd.value = (isAdmin() || getAppPermissions().includes('equipment:master:add'))
    && isPermEnabled('em', 'equipment:master:add')
  onQuery()
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.muted { color:#909399;font-size:12px }
.form-hint { margin-bottom:8px }
.form-row { display:flex;align-items:center;gap:8px;margin-bottom:10px }
.form-label { width:40px;font-size:13px;color:#606266;flex-shrink:0 }
.form-input { flex:1;border:1px solid #dcdfe6;border-radius:5px;padding:8px;font-size:14px;background:#fff }
.btn-row { display:flex;gap:8px }
.flex1 { flex:1 }
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

/* 新增设备浮层 */
.form-item { margin-bottom:10px }
.label { display:block;font-size:12px;color:#909399;margin-bottom:4px }
.picker { line-height:22px }
.placeholder-text { color:#c0c4cc }
.form-textarea { width:100%;box-sizing:border-box;border:1px solid #dcdfe6;border-radius:5px;padding:8px;font-size:14px;height:70px;background:#fff }
.sheet-mask { position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.45);z-index:900;display:flex;align-items:flex-end }
.sheet { width:100%;max-height:88vh;background:#fff;border-radius:12px 12px 0 0;display:flex;flex-direction:column }
.sheet-head { display:flex;justify-content:space-between;align-items:center;padding:12px 14px;border-bottom:1px solid #f0f0f0 }
.sheet-title { font-size:15px;font-weight:600;color:#303133 }
.sheet-close { font-size:13px;color:#909399;padding:4px 6px }
.sheet-body { flex:1;overflow-y:auto;padding:12px 14px;background:#f7f8fa }
.sheet-foot { display:flex;gap:8px;padding:10px 14px;border-top:1px solid #f0f0f0;padding-bottom:calc(10px + env(safe-area-inset-bottom)) }
</style>
