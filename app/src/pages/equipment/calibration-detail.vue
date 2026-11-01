<template>
  <view class="container">
    <view v-if="loading" class="empty muted">加载中...</view>
    <view v-else-if="!eq || !eq.id" class="empty muted">检验设备不存在</view>

    <template v-else>
      <!-- 设备档案 -->
      <view class="card">
        <view class="eq-top">
          <view class="eq-code">{{ eq.code || '—' }}</view>
          <view :class="['tag', stKey(eq.status)]">{{ stLabel(eq.status) }}</view>
        </view>
        <view class="eq-name">{{ eq.name || '—' }}</view>
        <view class="info-grid">
          <view class="info-item"><text class="info-label">规格型号</text><text class="info-value">{{ eq.spec || '—' }}</text></view>
          <view class="info-item"><text class="info-label">生产厂家</text><text class="info-value">{{ eq.manufacturer || '—' }}</text></view>
          <view class="info-item"><text class="info-label">所属车间</text><text class="info-value">{{ eq.workshop || '—' }}</text></view>
          <view class="info-item"><text class="info-label">存放位置</text><text class="info-value">{{ eq.location || '—' }}</text></view>
          <view class="info-item"><text class="info-label">量程与精度</text><text class="info-value">{{ eq.measureRange || '—' }}</text></view>
          <view class="info-item"><text class="info-label">校准周期</text><text class="info-value">{{ eq.intvMonths || 12 }} 个月</text></view>
          <view class="info-item"><text class="info-label">上次校准</text><text class="info-value">{{ eq.lastCalDate || '—' }}</text></view>
          <view class="info-item"><text class="info-label">到期日</text><text class="info-value">{{ eq.nextCalDate || '—' }}</text></view>
          <view class="info-item"><text class="info-label">最近机构</text><text class="info-value">{{ eq.lastOrg || '—' }}</text></view>
          <view class="info-item"><text class="info-label">最近证书号</text><text class="info-value">{{ eq.lastCertNo || '—' }}</text></view>
        </view>
        <view v-if="eq.remark" class="muted remark">备注: {{ eq.remark }}</view>
        <view v-if="dueText" :class="['alert', dueKey]">{{ dueText }}</view>
        <view class="btn-row">
          <button v-if="canUpload" class="btn-primary flex1" @click="openRec">＋ 上传校准记录</button>
          <button class="btn-plain flex1" :disabled="loading" @click="reload">刷新</button>
        </view>
      </view>

      <!-- 校准记录 -->
      <view class="section-title">校准记录 · 证书与报告</view>
      <view v-if="recDenied" class="empty muted">无权限查看校准记录 (需 qa:calibration-record:list)</view>
      <view v-else-if="!records.length" class="empty muted">暂无校准记录，点上方「上传校准记录」录入</view>
      <view v-else class="card rec-card" v-for="r in records" :key="r.id">
        <view class="rec-top">
          <view class="rec-date">{{ r.calDate || '—' }}<text class="muted"> · {{ r.recordNo || '' }}</text></view>
          <view :class="['tag', resKey(r.result)]">{{ resLabel(r.result) }}</view>
        </view>
        <view class="rec-meta muted">
          <template v-if="r.calOrg">机构 {{ r.calOrg }}</template>
          <template v-if="r.certNo"> · 证书号 {{ r.certNo }}</template>
          <template v-if="r.validUntil"> · 有效期至 {{ r.validUntil }}</template>
        </view>
        <view v-if="r.deviation" class="deviation">偏差: {{ r.deviation }}</view>
        <view v-if="r.reviewer" class="muted">审核 {{ r.reviewer }} · {{ r.reviewTime || '' }}</view>

        <view class="file-row">
          <view v-if="r.certFile" class="file-btn" @click="viewFile(r.certFile, '校准证书')">📎 查看证书</view>
          <view v-else class="file-empty muted">无证书附件</view>
          <view v-if="r.reportPdf" class="file-btn" @click="viewFile(r.reportPdf, '校准报告')">📄 查看报告</view>
          <view v-else class="file-empty muted">无报告文件</view>
        </view>

        <view v-if="canCheck && r.result === 'PENDING'" class="rec-actions">
          <button class="btn-plain flex1" @click="openCheck(r, 'FAILED')">判不合格</button>
          <button class="btn-primary flex1" @click="openCheck(r, 'PASSED')">判合格</button>
        </view>
      </view>
    </template>

    <!-- 上传校准记录浮层 -->
    <view v-if="showRec" class="sheet-mask" @click="closeRec">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-title">上传校准记录</text>
          <text class="sheet-close" @click="closeRec">关闭</text>
        </view>
        <view class="sheet-body">
          <view class="form-item">
            <text class="label">校准日期 *</text>
            <picker mode="date" :value="recForm.calDate || today" @change="onPickCalDate">
              <view :class="['form-input', 'picker', recForm.calDate ? '' : 'placeholder-text']">
                {{ recForm.calDate || '请选择校准日期' }}
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="label">有效期至 (留空=校准日期+周期)</text>
            <picker mode="date" :value="recForm.validUntil || today" @change="onPickValidUntil">
              <view :class="['form-input', 'picker', recForm.validUntil ? '' : 'placeholder-text']">
                {{ recForm.validUntil || '自动按校准周期推算' }}
              </view>
            </picker>
          </view>
          <view class="form-item">
            <text class="label">校准机构</text>
            <input class="form-input" v-model="recForm.calOrg" placeholder="如 舟山市计量检定所" />
          </view>
          <view class="form-item">
            <text class="label">证书编号</text>
            <input class="form-input" v-model="recForm.certNo" placeholder="选填" />
          </view>
          <view class="form-item">
            <text class="label">校准证书</text>
            <view class="file-upload">
              <text :class="['file-name', recForm.certFile ? '' : 'placeholder-text']">
                {{ recForm.certFile ? fileNameOf(recForm.certFile) : '未上传 (拍照或选 PDF)' }}
              </text>
              <view class="file-ops">
                <text v-if="recForm.certFile" class="link" @click="viewFile(recForm.certFile, '校准证书')">查看</text>
                <text class="link" @click="pickAttachment('certFile', '校准证书')">{{ recForm.certFile ? '重传' : '上传' }}</text>
              </view>
            </view>
          </view>
          <view class="form-item">
            <text class="label">校准报告</text>
            <view class="file-upload">
              <text :class="['file-name', recForm.reportPdf ? '' : 'placeholder-text']">
                {{ recForm.reportPdf ? fileNameOf(recForm.reportPdf) : '未上传 (PDF 或报告照片)' }}
              </text>
              <view class="file-ops">
                <text v-if="recForm.reportPdf" class="link" @click="viewFile(recForm.reportPdf, '校准报告')">查看</text>
                <text class="link" @click="pickAttachment('reportPdf', '校准报告')">{{ recForm.reportPdf ? '重传' : '上传' }}</text>
              </view>
            </view>
          </view>
          <view class="form-item">
            <text class="label">备注</text>
            <textarea class="form-textarea" v-model="recForm.remark" maxlength="200" placeholder="选填, 最多 200 字" />
          </view>
          <view class="muted">上传后为「待审核」，由有审核权限的人员确认合格/不合格后才会更新设备到期日</view>
        </view>
        <view class="sheet-foot">
          <button class="btn-plain flex1" @click="closeRec">取消</button>
          <button class="btn-primary flex1" :disabled="saving" @click="onSubmitRec">
            {{ saving ? '提交中...' : '提交' }}
          </button>
        </view>
      </view>
    </view>

    <!-- 审核浮层 -->
    <view v-if="showCheck" class="sheet-mask" @click="closeCheck">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-title">{{ checkForm.result === 'PASSED' ? '审核判定：合格' : '审核判定：不合格' }}</text>
          <text class="sheet-close" @click="closeCheck">关闭</text>
        </view>
        <view class="sheet-body">
          <view class="muted" style="margin-bottom:8px">
            记录 {{ checkRecord && checkRecord.recordNo }} · 校准日期 {{ checkRecord && checkRecord.calDate }}
          </view>
          <view class="form-item">
            <text class="label">偏差/不合格说明{{ checkForm.result === 'FAILED' ? ' *' : '' }}</text>
            <textarea class="form-textarea" v-model="checkForm.deviation" maxlength="200"
              :placeholder="checkForm.result === 'FAILED' ? '必填: 说明偏差或不合格原因' : '选填'" />
          </view>
          <view class="form-item">
            <text class="label">审核人</text>
            <input class="form-input" v-model="checkForm.reviewer" placeholder="留空=当前登录人" />
          </view>
          <view v-if="checkForm.result === 'PASSED'" class="muted">合格 → 设备到期日按本次校准日期 + 周期重算</view>
          <view v-else class="muted warn-text">不合格 → 设备立即标记为「已到期」，需重新送检</view>
        </view>
        <view class="sheet-foot">
          <button class="btn-plain flex1" @click="closeCheck">取消</button>
          <button class="btn-primary flex1" :disabled="checking" @click="submitCheck">
            {{ checking ? '提交中...' : '确认' }}
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import api from '../../api/index.js'
import { guardPage, isPermEnabled } from '../../utils/appModuleEnabled.js'
import { isAdmin, getAppPermissions } from '../../utils/permission.js'
import { pickAndUploadImage, pickAndUploadDocument } from '../../utils/upload.js'
import { openAttachment, fileNameOf } from '../../utils/openFile.js'

const id = ref(null)
const eq = ref({})
const records = ref([])
const loading = ref(false)
const recDenied = ref(false)
const today = todayStr()

const canUpload = ref(false)
const canCheck = ref(false)
const saving = ref(false)
const checking = ref(false)
const showRec = ref(false)
const showCheck = ref(false)
const recForm = reactive(emptyRec())
const checkForm = reactive({ result: 'PASSED', deviation: '', reviewer: '' })
const checkRecord = ref(null)

function todayStr() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
function emptyRec() {
  return { calDate: '', validUntil: '', calOrg: '', certNo: '', certFile: '', reportPdf: '', remark: '' }
}

const dueText = computed(() => {
  const n = daysLeft(eq.value && eq.value.nextCalDate)
  if (n == null) return ''
  if (n < 0) return '⚠ 已逾期 ' + Math.abs(n) + ' 天未检定，请立即安排送检'
  if (n <= 30) return '⚠ 距到期 ' + n + ' 天，请尽快安排送检'
  return ''
})
const dueKey = computed(() => {
  const n = daysLeft(eq.value && eq.value.nextCalDate)
  if (n == null) return ''
  if (n < 0) return 'alert-danger'
  if (n <= 30) return 'alert-warn'
  return ''
})

function daysLeft(dateStr) {
  if (!dateStr) return null
  const d = new Date(String(dateStr).substring(0, 10) + 'T00:00:00')
  if (isNaN(d.getTime())) return null
  const t = new Date(today + 'T00:00:00')
  return Math.round((d.getTime() - t.getTime()) / 86400000)
}
function stLabel(s) {
  const m = { VALID: '有效', EXPIRING_SOON: '即将到期', EXPIRED: '已到期', SCRAP: '报废' }
  return m[s] || s || '—'
}
function stKey(s) {
  const m = { VALID: 'tag-ok', EXPIRING_SOON: 'tag-warn', EXPIRED: 'tag-danger', SCRAP: 'tag-info' }
  return m[s] || 'tag-info'
}
function resLabel(r) {
  const m = { PENDING: '待审核', PASSED: '合格', FAILED: '不合格' }
  return m[r] || r || '待审核'
}
function resKey(r) {
  const m = { PENDING: 'tag-info', PASSED: 'tag-ok', FAILED: 'tag-danger' }
  return m[r] || 'tag-info'
}
function pickArr(r) {
  if (!r) return []
  if (Array.isArray(r)) return r
  return r.records || r.list || []
}

async function loadEq() {
  const r = await api.qaCalEqDetail(id.value)
  eq.value = r || {}
}
async function loadRecords() {
  recDenied.value = false
  try {
    const r = await api.qaCalRecPage({ equipmentId: id.value, pageNum: 1, pageSize: 100 })
    records.value = pickArr(r)
  } catch (e) {
    records.value = []
    if (e && e.code === 403) { recDenied.value = true; return }
    throw e
  }
}
async function reload() {
  loading.value = true
  try {
    await loadEq()
    await loadRecords()
  } catch (e) {
    console.warn('[qa-cal-detail.reload] 失败:', e)
    if (e && e.code === 401) return
    uni.showToast({ title: (e && e.msg) || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function openRec() {
  if (!canUpload.value) return
  Object.assign(recForm, emptyRec())
  recForm.calDate = today
  showRec.value = true
}
function closeRec() {
  if (saving.value) return
  showRec.value = false
}
function onPickCalDate(e) { recForm.calDate = e.detail.value }
function onPickValidUntil(e) {
  // 再点一次同一天 = 取消 (picker 没有清空能力)
  recForm.validUntil = recForm.validUntil === e.detail.value ? '' : e.detail.value
}

async function pickAttachment(key, label) {
  uni.showActionSheet({
    itemList: ['拍照上传', '从相册选择', '选择文件 (PDF/文档)'],
    success: async (res) => {
      try {
        uni.showLoading({ title: '上传中...' })
        let r
        if (res.tapIndex === 0) r = await pickAndUploadImage({ sourceType: ['camera'] })
        else if (res.tapIndex === 1) r = await pickAndUploadImage({ sourceType: ['album'] })
        else r = await pickAndUploadDocument()
        uni.hideLoading()
        recForm[key] = r.url
        uni.showToast({ title: label + '已上传', icon: 'success' })
      } catch (err) {
        uni.hideLoading()
        // uni.chooseImage 取消时 reject 的是 { errMsg: 'chooseImage:fail cancel' }, 不是 Error
        const raw = (err && (err.errMsg || err.message || err.msg)) || ''
        if (String(raw).toLowerCase().indexOf('cancel') >= 0) return
        uni.showModal({ title: '上传失败', content: raw || '上传失败', showCancel: false })
      }
    }
  })
}

async function viewFile(url, label) {
  try {
    await openAttachment(url, label)
  } catch (e) {
    uni.showToast({ title: (e && (e.message || e.msg)) || '打开失败', icon: 'none' })
  }
}

async function onSubmitRec() {
  if (!recForm.calDate) { uni.showToast({ title: '请选择校准日期', icon: 'none' }); return }
  const payload = {
    equipmentId: id.value,
    calDate: recForm.calDate,
    calOrg: (recForm.calOrg || '').trim(),
    certNo: (recForm.certNo || '').trim(),
    certFile: recForm.certFile || '',
    reportPdf: recForm.reportPdf || '',
    remark: (recForm.remark || '').trim(),
    result: 'PENDING'
  }
  if (recForm.validUntil) payload.validUntil = recForm.validUntil
  saving.value = true
  try {
    await api.qaCalRecSave(payload)
    uni.showToast({ title: '校准记录已提交', icon: 'success' })
    showRec.value = false
    Object.assign(recForm, emptyRec())
    await reload()
  } catch (e) {
    console.warn('[qa-cal-detail.onSubmitRec] 失败:', e)
    if (e && e.code === 401) return
    uni.showModal({ title: '提交失败', content: (e && e.msg) || '提交失败, 请重试', showCancel: false })
  } finally {
    saving.value = false
  }
}

function openCheck(rec, result) {
  checkRecord.value = rec
  checkForm.result = result
  checkForm.deviation = ''
  checkForm.reviewer = ''
  showCheck.value = true
}
function closeCheck() {
  if (checking.value) return
  showCheck.value = false
}
async function submitCheck() {
  if (!checkRecord.value) return
  if (checkForm.result === 'FAILED' && !(checkForm.deviation || '').trim()) {
    uni.showToast({ title: '判不合格必须填写原因', icon: 'none' })
    return
  }
  checking.value = true
  try {
    await api.qaCalRecCheck(checkRecord.value.id, {
      result: checkForm.result,
      deviation: (checkForm.deviation || '').trim(),
      reviewer: (checkForm.reviewer || '').trim()
    })
    uni.showToast({ title: '审核完成', icon: 'success' })
    showCheck.value = false
    await reload()
  } catch (e) {
    console.warn('[qa-cal-detail.submitCheck] 失败:', e)
    if (e && e.code === 401) return
    uni.showModal({ title: '审核失败', content: (e && e.msg) || '审核失败, 请重试', showCancel: false })
  } finally {
    checking.value = false
  }
}

onLoad((q) => {
  if (!guardPage('em')) return
  id.value = q && q.id ? q.id : null
  const appPerms = getAppPermissions()
  canUpload.value = (isAdmin() || appPerms.includes('qa:calibration-record:add'))
    && isPermEnabled('em', 'qa:calibration-record:add')
  canCheck.value = (isAdmin() || appPerms.includes('qa:calibration:check'))
    && isPermEnabled('em', 'qa:calibration:check')
  if (!id.value) return
  reload()
})
</script>

<style scoped>
.container { padding: 12px; padding-bottom: 40px; }
.muted { color:#909399;font-size:12px }
.empty { text-align:center;color:#909399;padding:30px;font-size:13px }
.btn-row { display:flex;gap:8px;margin-top:10px }
.flex1 { flex:1 }
.btn-primary { background:#1e6091;color:#fff;border:none;border-radius:6px;padding:10px 20px;font-size:14px }
.btn-primary[disabled] { opacity:0.5 }
.btn-plain { background:#f4f4f5;color:#606266;border:none;border-radius:6px;padding:8px 16px;font-size:13px }
.eq-top { display:flex;justify-content:space-between;align-items:center }
.eq-code { font-size:14px;font-weight:700;color:#303133 }
.eq-name { font-size:16px;font-weight:600;margin:4px 0 8px }
.info-grid { display:flex;flex-wrap:wrap }
.info-item { width:50%;box-sizing:border-box;padding:3px 0;font-size:12px;display:flex }
.info-label { color:#909399;width:64px;flex-shrink:0 }
.info-value { color:#303133;flex:1;min-width:0;word-break:break-all }
.remark { margin-top:6px }
.alert { margin-top:8px;font-size:12px;padding:8px 10px;border-radius:6px }
.alert-warn { background:#fdf6ec;color:#e6a23c }
.alert-danger { background:#fef0f0;color:#f56c6c }
.section-title { font-size:13px;font-weight:600;color:#303133;margin:14px 0 8px }
.rec-card { border-left:3px solid #1e6091 }
.rec-top { display:flex;justify-content:space-between;align-items:center }
.rec-date { font-size:14px;font-weight:600;color:#303133 }
.rec-meta { margin-top:4px }
.deviation { margin-top:4px;font-size:12px;color:#f56c6c }
.file-row { display:flex;gap:12px;margin-top:8px;flex-wrap:wrap }
.file-btn { font-size:12px;color:#1e6091;background:#ecf5ff;border-radius:4px;padding:4px 10px }
.file-empty { font-size:12px }
.rec-actions { display:flex;gap:8px;margin-top:10px }
.tag { font-size:11px;padding:2px 8px;border-radius:10px }
.tag-info { background:#f0f2f5;color:#606266 }
.tag-warn { background:#fdf6ec;color:#e6a23c }
.tag-ok { background:#f0f9eb;color:#67c23a }
.tag-danger { background:#fef0f0;color:#f56c6c }
.warn-text { color:#f56c6c }

/* 浮层 */
.form-item { margin-bottom:10px }
.label { display:block;font-size:12px;color:#909399;margin-bottom:4px }
.form-input { flex:1;box-sizing:border-box;border:1px solid #dcdfe6;border-radius:5px;padding:8px;font-size:14px;background:#fff;width:100% }
.picker { line-height:22px }
.placeholder-text { color:#c0c4cc }
.form-textarea { width:100%;box-sizing:border-box;border:1px solid #dcdfe6;border-radius:5px;padding:8px;font-size:14px;height:70px;background:#fff }
.file-upload { display:flex;justify-content:space-between;align-items:center;border:1px solid #dcdfe6;border-radius:5px;padding:8px;background:#fff }
.file-name { font-size:13px;flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap }
.file-ops { display:flex;gap:10px;flex-shrink:0;margin-left:8px }
.link { font-size:12px;color:#1e6091 }
.sheet-mask { position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.45);z-index:900;display:flex;align-items:flex-end }
.sheet { width:100%;max-height:88vh;background:#fff;border-radius:12px 12px 0 0;display:flex;flex-direction:column }
.sheet-head { display:flex;justify-content:space-between;align-items:center;padding:12px 14px;border-bottom:1px solid #f0f0f0 }
.sheet-title { font-size:15px;font-weight:600;color:#303133 }
.sheet-close { font-size:13px;color:#909399;padding:4px 6px }
.sheet-body { flex:1;overflow-y:auto;padding:12px 14px;background:#f7f8fa }
.sheet-foot { display:flex;gap:8px;padding:10px 14px;border-top:1px solid #f0f0f0;padding-bottom:calc(10px + env(safe-area-inset-bottom)) }
</style>
