<template>
  <view class="container">
    <!-- 统计 + 检定提醒 -->
    <view class="card">
      <view class="stat-row">
        <view class="stat-cell">
          <view class="stat-num">{{ stats.total }}</view>
          <view class="stat-label">检验设备</view>
        </view>
        <view class="stat-cell">
          <view class="stat-num ok">{{ stats.valid }}</view>
          <view class="stat-label">有效</view>
        </view>
        <view class="stat-cell">
          <view class="stat-num warn">{{ stats.soon }}</view>
          <view class="stat-label">30 天内到期</view>
        </view>
        <view class="stat-cell">
          <view class="stat-num danger">{{ stats.expired }}</view>
          <view class="stat-label">已到期</view>
        </view>
      </view>
      <view v-if="stats.due" class="alert">
        ⚠ {{ stats.due }} 台检验设备检定到期或即将到期，请尽快安排送检
      </view>
      <view class="btn-row">
        <button v-if="canAdd" class="btn-primary flex1" @click="openAdd">＋ 新增检验设备</button>
        <button class="btn-plain flex1" :disabled="loading" @click="reload">刷新</button>
      </view>
    </view>

    <!-- 筛选 + 搜索 -->
    <view class="chip-row">
      <view v-for="f in FILTERS" :key="f.value"
        :class="['chip', filter === f.value ? 'chip-on' : '']" @click="filter = f.value">
        {{ f.label }}<text v-if="f.value === 'EXPIRING_SOON' && stats.soon"> {{ stats.soon }}</text>
        <text v-if="f.value === 'EXPIRED' && stats.expired"> {{ stats.expired }}</text>
      </view>
    </view>
    <view class="card">
      <input class="form-input" v-model="keyword" placeholder="搜索设备名称 / 编号 / 型号" confirm-type="search" />
    </view>

    <!-- 列表 -->
    <view v-if="loading" class="empty muted">加载中...</view>
    <view v-else-if="!filtered.length" class="empty muted">
      {{ items.length ? '没有符合条件的设备' : '暂无检验设备，点上方「新增检验设备」建立台账' }}
    </view>
    <template v-else>
      <view class="card eq-card" v-for="eq in filtered" :key="eq.id" @click="openDetail(eq)">
        <view class="eq-top">
          <view class="eq-code">{{ eq.code || '—' }}</view>
          <view :class="['tag', stKey(eq.status)]">{{ stLabel(eq.status) }}</view>
        </view>
        <view class="eq-name">{{ eq.name || '—' }}</view>
        <view class="eq-meta muted">
          <template v-if="eq.spec">{{ eq.spec }}</template>
          <template v-if="eq.workshop"> · {{ eq.workshop }}</template>
          <template v-if="eq.location"> · {{ eq.location }}</template>
        </view>
        <view class="eq-cal">
          <text class="muted">上次 {{ eq.lastCalDate || '—' }}</text>
          <text :class="['due-text', dueKey(eq)]">{{ dueText(eq) }}</text>
        </view>
        <view class="eq-actions">
          <text class="muted">周期 {{ eq.intvMonths || 12 }} 个月</text>
          <text class="link">校准记录 ›</text>
        </view>
      </view>
    </template>

    <!-- 新增检验设备浮层 -->
    <view v-if="showAdd" class="sheet-mask" @click="closeAdd">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-title">新增检验设备</text>
          <text class="sheet-close" @click="closeAdd">关闭</text>
        </view>
        <view class="sheet-body">
          <view class="form-item">
            <text class="label">设备名称 *</text>
            <input class="form-input" v-model="form.name" placeholder="如 数显测厚仪" />
          </view>
          <view class="form-item">
            <text class="label">规格型号</text>
            <input class="form-input" v-model="form.spec" placeholder="如 0-10mm/0.001mm" />
          </view>
          <view class="form-item">
            <text class="label">生产厂家</text>
            <input class="form-input" v-model="form.manufacturer" placeholder="选填" />
          </view>
          <view class="form-item">
            <text class="label">所属车间</text>
            <input class="form-input" v-model="form.workshop" placeholder="如 吹膜车间" />
          </view>
          <view class="form-item">
            <text class="label">存放位置</text>
            <input class="form-input" v-model="form.location" placeholder="如 质检室" />
          </view>
          <view class="form-item">
            <text class="label">量程与精度</text>
            <input class="form-input" v-model="form.measureRange" placeholder="如 0-200mm ±0.02mm" />
          </view>
          <view class="form-item">
            <text class="label">上次校准日期</text>
            <picker mode="date" :value="form.lastCalDate || today" @change="onPickLastCal">
              <view :class="['form-input', 'picker', form.lastCalDate ? '' : 'placeholder-text']">
                {{ form.lastCalDate || '未选=按今天起算' }}
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="label">校准周期 (月)</text>
            <input class="form-input" type="number" v-model="form.intvMonths" placeholder="默认 12" />
          </view>
          <view class="form-item">
            <text class="label">备注</text>
            <textarea class="form-textarea" v-model="form.remark" maxlength="200" placeholder="选填, 最多 200 字" />
          </view>
          <view class="muted form-hint">到期日 = 上次校准日期 + 校准周期，到期前 30 天自动提醒</view>
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
import { onShow } from '@dcloudio/uni-app'
import api from '../../api/index.js'
import { guardPage, isPermEnabled } from '../../utils/appModuleEnabled.js'
import { isAdmin, getAppPermissions } from '../../utils/permission.js'

const FILTERS = [
  { value: 'ALL', label: '全部' },
  { value: 'VALID', label: '有效' },
  { value: 'EXPIRING_SOON', label: '即将到期' },
  { value: 'EXPIRED', label: '已到期' }
]

const items = ref([])
const loading = ref(false)
const keyword = ref('')
const filter = ref('ALL')
const canAdd = ref(false)
const showAdd = ref(false)
const saving = ref(false)
const form = reactive(emptyForm())

const today = todayStr()

function todayStr() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
function emptyForm() {
  return { name: '', spec: '', manufacturer: '', workshop: '', location: '', measureRange: '', lastCalDate: '', intvMonths: '12', remark: '' }
}

const stats = computed(() => {
  const s = { total: items.value.length, valid: 0, soon: 0, expired: 0, due: 0 }
  for (const eq of items.value) {
    if (eq.status === 'EXPIRING_SOON') s.soon++
    else if (eq.status === 'EXPIRED') s.expired++
    else if (eq.status !== 'SCRAP') s.valid++
  }
  s.due = s.soon + s.expired
  return s
})

const filtered = computed(() => {
  const kw = (keyword.value || '').trim().toLowerCase()
  return items.value.filter(eq => {
    if (filter.value !== 'ALL' && eq.status !== filter.value) return false
    if (!kw) return true
    return [eq.name, eq.code, eq.spec, eq.workshop].some(v => String(v || '').toLowerCase().includes(kw))
  })
})

function stLabel(s) {
  const m = { VALID: '有效', EXPIRING_SOON: '即将到期', EXPIRED: '已到期', SCRAP: '报废' }
  return m[s] || s || '—'
}
function stKey(s) {
  const m = { VALID: 'tag-ok', EXPIRING_SOON: 'tag-warn', EXPIRED: 'tag-danger', SCRAP: 'tag-info' }
  return m[s] || 'tag-info'
}
function daysLeft(dateStr) {
  if (!dateStr) return null
  const d = new Date(String(dateStr).substring(0, 10) + 'T00:00:00')
  if (isNaN(d.getTime())) return null
  const t = new Date(today + 'T00:00:00')
  return Math.round((d.getTime() - t.getTime()) / 86400000)
}
function dueText(eq) {
  const n = daysLeft(eq.nextCalDate)
  if (n == null) return '未登记到期日'
  if (n < 0) return '已逾期 ' + Math.abs(n) + ' 天'
  if (n === 0) return '今天到期'
  return '距到期 ' + n + ' 天'
}
function dueKey(eq) {
  const n = daysLeft(eq.nextCalDate)
  if (n == null) return ''
  if (n < 0) return 'due-danger'
  if (n <= 30) return 'due-warn'
  return 'due-ok'
}

function pickArr(r) {
  if (!r) return []
  if (Array.isArray(r)) return r
  return r.records || r.list || []
}

async function reload() {
  loading.value = true
  try {
    const r = await api.qaCalEqPage({ pageNum: 1, pageSize: 200 })
    items.value = pickArr(r)
  } catch (e) {
    items.value = []
    console.warn('[qa-calibration.reload] 失败:', e)
    if (e && e.code === 401) return
    uni.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function openDetail(eq) {
  uni.navigateTo({ url: '/pages/equipment/calibration-detail?id=' + eq.id })
}

function openAdd() {
  if (!canAdd.value) return
  Object.assign(form, emptyForm())
  form.lastCalDate = ''
  showAdd.value = true
}
function closeAdd() {
  if (saving.value) return
  showAdd.value = false
}
function onPickLastCal(e) {
  form.lastCalDate = e.detail.value
}

async function onSubmit() {
  const name = (form.name || '').trim()
  if (!name) { uni.showToast({ title: '请输入设备名称', icon: 'none' }); return }
  const intv = Number(form.intvMonths)
  if (form.intvMonths !== '' && (!intv || intv <= 0)) {
    uni.showToast({ title: '校准周期需大于 0', icon: 'none' })
    return
  }
  if (items.value.some(x => String(x.name || '').trim() === name && String(x.spec || '').trim() === String(form.spec || '').trim())) {
    uni.showToast({ title: '同名同型号设备已存在', icon: 'none' })
    return
  }
  const payload = {
    name,
    spec: (form.spec || '').trim(),
    manufacturer: (form.manufacturer || '').trim(),
    workshop: (form.workshop || '').trim(),
    location: (form.location || '').trim(),
    measureRange: (form.measureRange || '').trim(),
    intvMonths: intv || 12,
    remark: (form.remark || '').trim()
  }
  if (form.lastCalDate) payload.lastCalDate = form.lastCalDate
  saving.value = true
  try {
    await api.qaCalEqSave(payload)
    uni.showToast({ title: '设备已新增', icon: 'success' })
    showAdd.value = false
    Object.assign(form, emptyForm())
    await reload()
  } catch (e) {
    console.warn('[qa-calibration.onSubmit] 失败:', e)
    if (e && e.code === 401) return
    uni.showModal({ title: '新增失败', content: (e && e.msg) || '新增失败, 请重试', showCancel: false })
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  if (!guardPage('em')) return
  canAdd.value = (isAdmin() || getAppPermissions().includes('qa:calibration-equipment:add'))
    && isPermEnabled('em', 'qa:calibration-equipment:add')
  reload()
})

// 从详情页返回 (新增校准记录后到期日会变) → 刷新统计
onShow(() => {
  if (items.value.length) reload()
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.muted { color:#909399;font-size:12px }
.form-hint { margin-top:6px }
.btn-row { display:flex;gap:8px;margin-top:10px }
.flex1 { flex:1 }
.btn-primary { background:#1e6091;color:#fff;border:none;border-radius:6px;padding:10px 20px;font-size:14px }
.btn-primary[disabled] { opacity:0.5 }
.btn-plain { background:#f4f4f5;color:#606266;border:none;border-radius:6px;padding:8px 16px;font-size:13px }
.empty { text-align:center;color:#909399;padding:30px;font-size:13px }
.stat-row { display:flex }
.stat-cell { flex:1;text-align:center }
.stat-num { font-size:20px;font-weight:700;color:#303133 }
.stat-num.ok { color:#67c23a }
.stat-num.warn { color:#e6a23c }
.stat-num.danger { color:#f56c6c }
.stat-label { font-size:11px;color:#909399;margin-top:2px }
.alert { margin-top:10px;background:#fdf6ec;color:#e6a23c;font-size:12px;padding:8px 10px;border-radius:6px }
.chip-row { display:flex;gap:8px;margin:12px 0;flex-wrap:wrap }
.chip { font-size:12px;color:#606266;background:#fff;border:1px solid #dcdfe6;border-radius:14px;padding:5px 12px }
.chip-on { color:#fff;background:#1e6091;border-color:#1e6091 }
.form-input { flex:1;box-sizing:border-box;border:1px solid #dcdfe6;border-radius:5px;padding:8px;font-size:14px;background:#fff;width:100% }
.eq-card { border-left:3px solid #1e6091 }
.eq-top { display:flex;justify-content:space-between;align-items:center }
.eq-code { font-size:14px;font-weight:700;color:#303133 }
.eq-name { font-size:15px;font-weight:600;margin-top:4px }
.eq-meta { margin-top:4px }
.eq-cal { display:flex;justify-content:space-between;align-items:center;margin-top:6px }
.due-text { font-size:12px }
.due-warn { color:#e6a23c }
.due-danger { color:#f56c6c }
.due-ok { color:#67c23a }
.eq-actions { display:flex;justify-content:space-between;align-items:center;margin-top:8px;padding-top:8px;border-top:1px solid #f5f5f5 }
.link { font-size:12px;color:#1e6091 }
.tag { font-size:11px;padding:2px 8px;border-radius:10px }
.tag-info { background:#f0f2f5;color:#606266 }
.tag-warn { background:#fdf6ec;color:#e6a23c }
.tag-ok { background:#f0f9eb;color:#67c23a }
.tag-danger { background:#fef0f0;color:#f56c6c }

/* 新增浮层 */
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
