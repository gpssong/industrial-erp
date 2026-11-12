<template>
  <!-- 4 统计卡 (真后端) -->
  <div class="cal-stats">
    <div class="cal-stat s-blue"><small>检验设备</small><div class="cal-num"><b>{{ calStat.total }}</b><span>台</span></div></div>
    <div class="cal-stat s-green"><small>校准有效</small><div class="cal-num"><b>{{ calStat.valid }}</b><span>台</span></div></div>
    <div class="cal-stat s-orange"><small>30 天内到期</small><div class="cal-num"><b>{{ calStat.expiring }}</b><span>台</span></div></div>
    <div class="cal-stat s-red"><small>已到期</small><div class="cal-num"><b>{{ calStat.expired }}</b><span>台</span></div></div>
  </div>

  <!-- 待处理警告条 (动态: 30天到期 + 已到期合计) -->
  <div v-if="calStat.expiring + calStat.expired > 0" class="cal-alert">
    <el-icon><WarningFilled /></el-icon>
    <div class="cal-alert-text">
      <b>{{ calStat.expiring + calStat.expired }} 台设备需要处理</b>
      <small>{{ calStat.expiring }} 台 30 天内到期, {{ calStat.expired }} 台已到期.</small>
    </div>
    <el-button @click="onGenerateCal">生成校准计划</el-button>
  </div>

  <div class="cal-layout">
    <!-- 左: 设备台账 (真后端) -->
    <div class="cal-ledger">
      <div class="cal-ledger-head">
        <div><b>检验设备台账</b><small>鉴定、校准和报告统一归档 (v1.1.97 真后端)</small></div>
        <div style="display:flex; gap:8px; align-items:center;">
          <div class="cal-ledger-search">
            <el-icon><Search /></el-icon>
            <el-input v-model="calQuery" placeholder="搜索设备名称或编号" clearable />
          </div>
          <el-button type="primary" size="default" @click="onNewCalEq"><el-icon><Plus /></el-icon>新增校准设备</el-button>
        </div>
      </div>
      <div class="cal-table-head">
        <div class="cc-col c-dev">设备</div>
        <div class="cc-col c-spec">量程 / 精度</div>
        <div class="cc-col c-loc">位置</div>
        <div class="cc-col c-last">最近校准</div>
        <div class="cc-col c-next">下次校准</div>
        <div class="cc-col c-status">状态</div>
      </div>
      <div class="cal-table-body" v-loading="calLoading">
        <div
          v-for="d in filteredCalRows"
          :key="d.id"
          class="cal-table-row"
          :class="{ active: d.id === calSel?.id }"
          @click="onSelectCal(d)"
        >
          <div class="cc-col c-dev"><el-icon><Tools /></el-icon><span class="dev-name">{{ d.name }}<small class="dev-code mono">{{ d.code }}</small></span></div>
          <div class="cc-col c-spec">{{ d.measureRange || d.spec || '-' }}</div>
          <div class="cc-col c-loc">{{ d.location || '-' }}</div>
          <div class="cc-col c-last mono">{{ d.lastCalDate || '-' }}</div>
          <div class="cc-col c-next mono">{{ d.nextCalDate || '-' }}</div>
          <div class="cc-col c-status"><el-tag :type="calStatusTag(d.status)" size="small" effect="light">{{ calStatusLabel(d.status) }}</el-tag></div>
        </div>
        <div v-if="!calLoading && !filteredCalRows.length" class="cal-table-empty">暂无设备, 点「新增校准设备」建档</div>
      </div>
    </div>

    <!-- 右: 设备档案 + 校准记录 -->
    <div class="cal-profile">
      <div class="cal-profile-head">
        <div><small>检验设备档案</small><b>{{ calSel?.name || '未选择' }}</b><span class="dev-code mono">{{ calSel?.code }} · {{ calSel?.location || '-' }}</span></div>
        <el-tag :type="calSel ? calStatusTag(calSel.status) : 'info'" size="small" effect="light">{{ calSel ? calStatusLabel(calSel.status) : '未选择' }}</el-tag>
      </div>
      <div class="cal-profile-grid">
        <div class="cal-cell"><small>量程与精度</small><span>{{ calSel?.measureRange || calSel?.spec || '-' }}</span></div>
        <div class="cal-cell"><small>校准周期</small><span>{{ calSel ? (calSel.intvMonths + ' 个月') : '-' }}</span></div>
        <div class="cal-cell"><small>最近校准日期</small><span class="mono">{{ calSel?.lastCalDate || '-' }}</span></div>
        <div class="cal-cell"><small>下次校准日期</small><span class="mono">{{ calSel?.nextCalDate || '-' }}</span></div>
        <div class="cal-cell"><small>校准机构</small><span>{{ calSel?.lastOrg || '-' }}</span></div>
        <div class="cal-cell"><small>证书编号</small><span class="mono">{{ calSel?.lastCertNo || '-' }}</span></div>
      </div>

      <div class="cal-report">
        <div class="cal-report-head">
          <div><b>校准记录</b><small>共 {{ calRecords.length }} 条</small></div>
          <el-button size="small" type="primary" @click="onNewCalRec"><el-icon><Plus /></el-icon>新增校准</el-button>
        </div>
        <div class="cal-report-item" v-for="rec in calRecords" :key="rec.id" style="display:flex; justify-content:space-between; align-items:center;">
          <div class="cal-report-file">
            <el-icon><Document /></el-icon>
            <div>
              <b>{{ rec.certNo || '无证书号' }}</b>
              <small>校准编号: {{ rec.recordNo }} · {{ rec.calDate }} · {{ rec.result }}</small>
            </div>
          </div>
          <div>
            <el-button v-if="rec.result === 'PENDING'" size="small" plain @click="onCheckRec(rec)">审核</el-button>
            <el-button size="small" plain @click="onViewCal(rec)">查看</el-button>
          </div>
        </div>
        <div v-if="!calRecords.length" class="cal-table-empty" style="padding:12px;">该设备暂无校准记录</div>
      </div>
    </div>
  </div>

  <!-- =============== 弹窗: 新建校准设备 =============== -->
  <el-dialog v-model="newEqDialogVisible" title="新增校准设备" width="560px">
    <el-form :model="newEqForm" label-width="100">
      <el-form-item label="设备名称" required>
        <el-input v-model="newEqForm.name" placeholder="如: 数显测厚仪" />
      </el-form-item>
      <el-form-item label="规格型号">
        <el-input v-model="newEqForm.spec" placeholder="如: 0-10 mm / 0.001 mm" />
      </el-form-item>
      <el-form-item label="厂家">
        <el-input v-model="newEqForm.manufacturer" />
      </el-form-item>
      <el-form-item label="存放位置">
        <el-input v-model="newEqForm.location" placeholder="如: 质量检验室" />
      </el-form-item>
      <el-form-item label="所属车间">
        <el-input v-model="newEqForm.workshop" placeholder="如: 一车间" />
      </el-form-item>
      <el-form-item label="量程与精度">
        <el-input v-model="newEqForm.measureRange" />
      </el-form-item>
      <el-form-item label="校准周期 (月)">
        <el-input-number v-model="newEqForm.intvMonths" :min="1" :max="60" style="width: 100%" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="newEqDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="onNewCalEqSubmit">保存</el-button>
    </template>
  </el-dialog>

  <!-- =============== 弹窗: 新建校准记录 =============== -->
  <el-dialog v-model="newRecDialogVisible" title="新增校准记录" width="560px">
    <el-form :model="newRecForm" label-width="100">
      <el-form-item label="设备">
        <el-input :model-value="calSel ? calSel.code + ' · ' + calSel.name : ''" disabled />
      </el-form-item>
      <el-form-item label="校准日期" required>
        <el-date-picker v-model="newRecForm.calDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
      </el-form-item>
      <el-form-item label="有效期至">
        <el-date-picker v-model="newRecForm.validUntil" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
      </el-form-item>
      <el-form-item label="校准机构">
        <el-input v-model="newRecForm.calOrg" placeholder="如: 市计量质量监测研究院" />
      </el-form-item>
      <el-form-item label="证书编号">
        <el-input v-model="newRecForm.certNo" placeholder="如: JL-2026-TH-0186" />
      </el-form-item>
      <el-form-item label="证书附件 URL">
        <div style="display:flex; gap:8px; align-items:center; width:100%">
          <el-input v-model="newRecForm.certFile" placeholder="点击右侧上传证书附件" style="flex:1" />
          <el-button size="small" @click="onUploadCal">上传</el-button>
        </div>
      </el-form-item>
      <el-form-item label="报告 PDF URL">
        <el-input v-model="newRecForm.reportPdf" />
      </el-form-item>
      <el-form-item label="初始结果">
        <el-select v-model="newRecForm.result" style="width: 100%">
          <el-option label="待审核" value="PENDING" />
          <el-option label="合格" value="PASSED" />
          <el-option label="不合格" value="FAILED" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="newRecDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="onNewCalRecSubmit">保存</el-button>
    </template>
  </el-dialog>

  <!-- =============== 弹窗: 校准审核 =============== -->
  <el-dialog v-model="checkRecDialogVisible" title="校准审核" width="480px">
    <el-form :model="checkRecForm" label-width="100">
      <el-form-item label="审核结果">
        <el-radio-group v-model="checkRecForm.result">
          <el-radio value="PASSED">合格 (推设备 next_cal_date)</el-radio>
          <el-radio value="FAILED">不合格 (设备停用 / 返厂)</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="偏差说明">
        <el-input v-model="checkRecForm.deviation" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="审核人">
        <el-input v-model="checkRecForm.reviewer" placeholder="留空 = 当前登录用户" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="checkRecDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="onCheckRecSubmit">提交审核</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { WarningFilled, Search, Plus, Tools, Document } from '@element-plus/icons-vue'
import { calibrationEquipmentApi, calibrationRecordApi } from '@/api/qa'
import { pickImageFile } from '@/utils/upload'

// ============== 检验设备校准 ==============
const calList = ref([])
const calSel = ref(null)
const calQuery = ref('')
const calLoading = ref(false)
const calRecords = ref([])  // 当前选中设备的校准记录列表

async function loadCal() {
  calLoading.value = true
  try {
    const r = await calibrationEquipmentApi.listAll()
    if (r.code === 200) {
      calList.value = r.data || []
      if (!calSel.value && calList.value.length) await onSelectCal(calList.value[0])
    }
  } finally { calLoading.value = false }
}
async function onSelectCal(row) {
  calSel.value = row
  const r = await calibrationRecordApi.page({ equipmentId: row.id, pageNum: 1, pageSize: 20 })
  if (r.code === 200) calRecords.value = r.data?.records || []
}
const filteredCalRows = computed(() => {
  const q = calQuery.value.trim()
  if (!q) return calList.value
  return calList.value.filter(d =>
    (d.name || '').includes(q) || (d.code || '').includes(q) || (d.location || '').includes(q)
  )
})
function calStatusTag(status) {
  if (status === 'VALID') return 'success'
  if (status === 'EXPIRING_SOON') return 'warning'
  if (status === 'SCRAP') return 'info'
  return 'danger'  // EXPIRED
}
function calStatusLabel(status) {
  if (status === 'VALID') return '有效'
  if (status === 'EXPIRING_SOON') return '即将到期'
  if (status === 'SCRAP') return '报废'
  return '已到期'
}
const calStat = computed(() => {
  const total = calList.value.length
  const valid = calList.value.filter(c => c.status === 'VALID').length
  const expiring = calList.value.filter(c => c.status === 'EXPIRING_SOON').length
  const expired = calList.value.filter(c => c.status === 'EXPIRED').length
  return { total, valid, expiring, expired }
})

// 新增校准设备弹窗
const newEqDialogVisible = ref(false)
const newEqForm = ref({
  name: '', spec: '', manufacturer: '', location: '',
  workshop: '', measureRange: '', intvMonths: 12
})
function onNewCalEq() {
  newEqForm.value = { name: '', spec: '', manufacturer: '', location: '', workshop: '', measureRange: '', intvMonths: 12 }
  newEqDialogVisible.value = true
}
async function onNewCalEqSubmit() {
  if (!newEqForm.value.name) { ElMessage.warning('请填设备名称'); return }
  try {
    const r = await calibrationEquipmentApi.save(newEqForm.value)
    if (r.code === 200) {
      ElMessage.success('已新增校准设备')
      newEqDialogVisible.value = false
      await loadCal()
      if (r.data) await onSelectCal(r.data)
    } else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
}

// 新增校准记录弹窗
const newRecDialogVisible = ref(false)
const newRecForm = ref({
  equipmentId: null,
  calDate: null, validUntil: null,
  calOrg: '', certNo: '', certFile: '', reportPdf: '',
  result: 'PENDING', deviation: '', remark: ''
})
function onNewCalRec() {
  if (!calSel.value) { ElMessage.warning('请先选择一台设备'); return }
  newRecForm.value = {
    equipmentId: calSel.value.id,
    calDate: null, validUntil: null,
    calOrg: '', certNo: '', certFile: '', reportPdf: '',
    result: 'PENDING', deviation: '', remark: ''
  }
  newRecDialogVisible.value = true
}
async function onNewCalRecSubmit() {
  if (!newRecForm.value.calDate) { ElMessage.warning('请选择校准日期'); return }
  try {
    const r = await calibrationRecordApi.save(newRecForm.value)
    if (r.code === 200) {
      ElMessage.success('已新增校准记录, 等待审核')
      newRecDialogVisible.value = false
      await onSelectCal(calSel.value)  // 刷新记录列表
    } else ElMessage.error(r.msg || '保存失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '保存失败') }
}

// 校准审核弹窗
const checkRecDialogVisible = ref(false)
const checkRecForm = ref({ result: 'PASSED', deviation: '', reviewer: '' })
const checkRecTarget = ref(null)
function onCheckRec(row) {
  checkRecTarget.value = row
  checkRecForm.value = { result: 'PASSED', deviation: row.deviation || '', reviewer: '' }
  checkRecDialogVisible.value = true
}
async function onCheckRecSubmit() {
  if (!checkRecTarget.value) return
  try {
    const r = await calibrationRecordApi.check(checkRecTarget.value.id, checkRecForm.value)
    if (r.code === 200) {
      ElMessage.success('已审核, 状态 ' + checkRecForm.value.result)
      checkRecDialogVisible.value = false
      await onSelectCal(calSel.value)  // 刷新记录
      await loadCal()  // 设备状态也刷新 (EXPIRED → VALID)
    } else ElMessage.error(r.msg || '审核失败')
  } catch (e) { ElMessage.error((e && (e.msg || e.message)) || '审核失败') }
}

function onGenerateCal() { ElMessage.info('生成校准计划: 选中 30 天内到期的设备') }
function onUploadCal() {
  // 校准证书附件 → 真上传 /system/upload/file, 返回 URL 回填新校准记录表单 certFile (保存时随 record.certFile 落库)
  pickImageFile((url, name) => {
    newRecForm.value.certFile = url
    ElMessage.success('证书附件已上传: ' + name)
  })
}
function onViewCal(rec) {
  if (rec.reportPdf) window.open(rec.reportPdf, '_blank')
  else if (rec.certFile) window.open(rec.certFile, '_blank')
  else ElMessage.info('该记录未上传证书/报告 PDF')
}

onMounted(loadCal)
</script>

<style scoped lang="scss">
/* ===== 检验设备校准 (对齐 image #12-#13) ===== */
.cal-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
@media (max-width: 900px) { .cal-stats { grid-template-columns: repeat(2, 1fr); } }
.cal-stat {
  background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 14px 16px;
  position: relative; box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
  small { font-size: 12px; color: var(--muted); font-weight: 600; }
  .cal-num { display: flex; align-items: baseline; gap: 5px; margin-top: 8px; }
  .cal-num b { font-size: 30px; font-weight: 760; color: var(--text); line-height: 1; }
  .cal-num span { font-size: 12px; color: var(--muted); }
  &::before { content: ''; position: absolute; left: 0; top: 12px; bottom: 12px; width: 3px; border-radius: 2px; }
  &.s-blue::before { background: var(--primary-color); }
  &.s-green::before { background: var(--success); }
  &.s-orange::before { background: #e8920b; }
  &.s-red::before { background: var(--danger); }
}

.cal-alert {
  display: flex; align-items: center; gap: 12px;
  background: #fff8eb; border: 1px solid #f0cba1; border-radius: 12px; padding: 14px 18px;
  > .el-icon { color: #e8920b; font-size: 22px; flex: 0 0 auto; }
}
.cal-alert-text { flex: 1; }
.cal-alert-text b { font-size: 13px; font-weight: 700; color: #846e52; display: block; }
.cal-alert-text small { font-size: 11px; color: #9a8266; margin-top: 2px; display: block; }

.cal-layout { display: grid; grid-template-columns: 1fr 340px; gap: 16px; align-items: start; }
@media (max-width: 1100px) { .cal-layout { grid-template-columns: 1fr; } }

.cal-ledger { background: #fff; border: 1px solid var(--line); border-radius: 14px; overflow: hidden; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.cal-ledger-head {
  display: flex; align-items: center; justify-content: space-between; gap: 14px;
  padding: 16px 20px; border-bottom: 1px solid var(--line); flex-wrap: wrap;
}
.cal-ledger-head > div:first-child b { font-size: 15px; font-weight: 730; color: var(--text); display: block; }
.cal-ledger-head > div:first-child small { display: block; font-size: 12px; color: var(--muted); margin-top: 3px; }
.cal-ledger-search { display: flex; align-items: center; gap: 6px; width: 240px; background: #fff; border: 1px solid var(--line); border-radius: 8px; padding: 0 10px;
  .el-icon { color: #9aa7b5; }
  .el-input :deep(.el-input__inner) { background: transparent; }
}

.cal-table-head { display: flex; background: #f7f9fc; padding: 12px 20px; font-size: 12px; color: var(--muted); font-weight: 650; }
.cal-table-body { display: flex; flex-direction: column; }
.cal-table-row {
  display: flex; align-items: center; padding: 14px 20px; border-bottom: 1px solid #eef1f4; font-size: 13px; cursor: pointer;
  position: relative;
  &:last-child { border-bottom: 0; }
  &:hover { background: #fbfcfe; }
  &.active { background: #f4f9ff; &::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--primary-color); } }
}
.cal-table-empty { padding: 30px; text-align: center; color: var(--muted); font-size: 13px; }
.cc-col { white-space: nowrap; display: flex; align-items: center; gap: 6px; }
.cc-col > .el-icon { color: var(--primary-color); }
.c-dev { flex: 0 0 150px; font-weight: 600; color: var(--text); flex-direction: column; align-items: flex-start; gap: 2px; }
.dev-name { font-size: 13px; font-weight: 600; color: var(--text); }
.dev-code { font-size: 11px; color: #9aa7b5; }
.c-spec { flex: 1; color: #5a6b7d; }
.c-loc { flex: 0 0 100px; color: #5a6b7d; }
.c-last { flex: 0 0 110px; }
.c-next { flex: 0 0 110px; }
.c-status { flex: 0 0 90px; justify-content: flex-end; }
@media (max-width: 900px) { .cal-table-row { flex-wrap: wrap; gap: 8px; } .cc-col { flex: 1 1 40%; } }

// 设备档案
.cal-profile { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 18px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.cal-profile-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.cal-profile-head small { font-size: 11px; color: var(--primary-color); font-weight: 600; display: block; }
.cal-profile-head b { font-size: 16px; font-weight: 740; color: var(--text); display: block; margin: 4px 0 2px; }
.cal-profile-head .dev-code { font-size: 11px; color: #9aa7b5; }

.cal-profile-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.cal-cell { background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 8px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.cal-cell small { font-size: 11px; color: var(--muted); }
.cal-cell span { font-size: 13px; color: var(--text); font-weight: 600; }

.cal-report { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 16px; }
.cal-report-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 12px; }
.cal-report-head b { font-size: 13px; font-weight: 720; color: var(--text); display: block; }
.cal-report-head small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
.cal-report-item {
  display: flex; align-items: center; gap: 12px; padding: 12px;
  background: #f4f9ff; border: 1px solid #d6e4f5; border-radius: 10px;
}
.cal-report-file { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.cal-report-file > .el-icon { color: var(--primary-color); font-size: 20px; flex: 0 0 auto; }
.cal-report-file b { font-size: 12px; color: var(--text); display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cal-report-file small { display: block; font-size: 10px; color: var(--muted); margin-top: 2px; }
.cal-report-actions { display: flex; gap: 10px; margin-top: 12px; }
.cal-report-actions .el-button { flex: 1; }

.mono { font-family: ui-monospace, 'SF Mono', monospace; font-size: 12px; color: #4b5a6b; }
</style>
