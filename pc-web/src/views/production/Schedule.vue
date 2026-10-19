<template>
  <div class="page">
    <!-- 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">生产管理 / 生产排程</div>
        <h1 class="xk-title">生产排程与设备 OEE</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button type="primary" @click="openAdd"><el-icon><Plus /></el-icon>新增排程</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" class="sch-tabs">
      <!-- 排程甘特 -->
      <el-tab-pane label="排程甘特" name="gantt">
        <div class="toolbar">
          <el-select v-model="ganttEquipmentId" filterable placeholder="选设备看排程时间轴" style="width:260px" @change="loadGantt">
            <el-option v-for="e in equipmentList" :key="e.id" :label="e.equipmentName || ('设备#' + e.id)" :value="e.id" />
          </el-select>
          <span class="muted">同设备重叠时段会在后端记冲突日志 (仅提示不阻断)</span>
        </div>
        <el-empty v-if="!ganttEquipmentId" description="请选择设备查看排程时间轴" />
        <div v-else class="gantt" v-loading="ganttLoading">
          <div class="gantt-timescale">
            <span v-for="h in hours" :key="h">{{ h }}:00</span>
          </div>
          <div v-for="row in gantt" :key="row.id" class="gantt-row">
            <div class="gantt-label">{{ row.equipmentName || row.equipmentCode || '#' + row.equipmentId }}<br />
              <small class="muted">{{ row.processName || '工序#' + row.processNo }}</small></div>
            <div class="gantt-track">
              <div class="gantt-bar"
                   :style="barStyle(row)"
                   :class="'gantt-' + row.status">
                <span>{{ row.prdOrderNo || '' }}</span>
              </div>
            </div>
          </div>
          <div v-if="!gantt.length" class="gantt-empty">该设备暂无排程</div>
        </div>
      </el-tab-pane>

      <!-- 排程列表 -->
      <el-tab-pane label="排程列表" name="list">
        <el-table :data="records" border stripe v-loading="listLoading" empty-text="暂无排程">
          <el-table-column prop="prdOrderNo" label="工单" width="150" />
          <el-table-column label="设备" min-width="150">
            <template #default="{ row }">{{ row.equipmentName || ('设备#' + row.equipmentId) }}</template>
          </el-table-column>
          <el-table-column prop="processName" label="工序" width="120" />
          <el-table-column label="计划时段" width="220">
            <template #default="{ row }">{{ fmt(row.planStart) }} ~ {{ fmt(row.planEnd) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="OEE" width="80" align="right">
            <template #default="{ row }">{{ row.oee != null ? row.oee + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button v-if="userStore.hasPerm('production:schedule:edit')"
                         link type="danger" size="small" @click="onCancel(row)">取消</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination v-model:current-page="pageNum" v-model:page-size="pageSize"
            :total="total" :page-sizes="[10, 20, 50]" background
            layout="total, sizes, prev, pager, next"
            @current-change="loadList" @size-change="onPageSizeChange" />
        </div>
      </el-tab-pane>

      <!-- 设备 OEE -->
      <el-tab-pane label="设备 OEE" name="oee">
        <div class="toolbar">
          <el-select v-model="oeeEquipmentId" filterable placeholder="选设备看 OEE" style="width:260px" @change="loadOee">
            <el-option v-for="e in equipmentList" :key="e.id" :label="e.equipmentName || ('设备#' + e.id)" :value="e.id" />
          </el-select>
        </div>
        <el-empty v-if="!oeeEquipmentId" description="请选择设备计算 OEE" />
        <div v-else class="oee-cards" v-loading="oeeLoading">
          <div class="oee-card"><b>{{ oee.oee }}%</b><small>OEE</small></div>
          <div class="oee-card"><b>{{ oee.availability }}%</b><small>稼动率</small></div>
          <div class="oee-card"><b>{{ oee.performance }}%</b><small>性能率</small></div>
          <div class="oee-card"><b>{{ oee.quality }}%</b><small>良品率</small></div>
          <div class="oee-card"><b>{{ oee.planMinutes }}</b><small>计划工时(分)</small></div>
          <div class="oee-card"><b>{{ oee.activeMinutes }}</b><small>稼动工时(分)</small></div>
          <div class="oee-card"><b>{{ oee.downtimeMinutes }}</b><small>停机(分)</small></div>
          <div class="oee-card"><b>{{ oee.records }}</b><small>耗时记录数</small></div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增排程弹窗 -->
    <el-dialog v-model="dialogVisible" title="新增排程" width="600px" destroy-on-close>
      <el-form :model="form" label-width="90px">
        <el-form-item label="生产工单">
          <el-select v-model="form.prdOrderId" filterable placeholder="选工单" style="width:100%" @change="onOrderChange">
            <el-option v-for="o in orders" :key="o.id" :label="o.billNo" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="设备">
          <el-select v-model="form.equipmentId" filterable placeholder="选设备" style="width:100%" @change="onEquipChange">
            <el-option v-for="e in equipmentList" :key="e.id" :label="e.equipmentName || ('设备#' + e.id)" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="工序">
          <el-input v-model="form.processName" placeholder="工序名 (可与设备绑定工序不一致, 仅提示)" />
        </el-form-item>
        <el-form-item label="计划开工">
          <el-date-picker v-model="form.planStart" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="计划完工">
          <el-date-picker v-model="form.planEnd" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { mesApi, prdOrderApi } from '@/api/production'
import { equipmentApi } from '@/api/equipment'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const tab = ref('gantt')
const equipmentList = ref([])
const orders = ref([])

// ===== 甘特 =====
const ganttEquipmentId = ref(null)
const gantt = ref([])
const ganttLoading = ref(false)
const hours = Array.from({ length: 25 }, (_, i) => i)   // 0..24

function barStyle(row) {
  const s = new Date(row.planStart), e = new Date(row.planEnd)
  const dayMin = s.getHours() * 60 + s.getMinutes()
  const endMin = e.getHours() * 60 + e.getMinutes()
  const startPct = Math.max(0, dayMin / 1440 * 100)
  const widthPct = Math.max(2, Math.min(100 - startPct, (endMin - dayMin) / 1440 * 100))
  return { left: startPct + '%', width: widthPct + '%' }
}

async function loadGantt() {
  if (!ganttEquipmentId.value) { gantt.value = []; return }
  ganttLoading.value = true
  try {
    const r = await mesApi.scheduleGantt(ganttEquipmentId.value)
    gantt.value = r.data || []
  } catch { gantt.value = [] } finally { ganttLoading.value = false }
}

// ===== 列表 =====
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const listLoading = ref(false)

async function loadList() {
  listLoading.value = true
  try {
    const r = await mesApi.schedulePage({ pageNum: pageNum.value, pageSize: pageSize.value })
    records.value = r.data?.records || []
    total.value = r.data?.total || 0
  } catch { records.value = [] } finally { listLoading.value = false }
}
function onPageSizeChange(s) { pageSize.value = s; pageNum.value = 1; loadList() }

function statusTag(s) {
  return s === 'DONE' ? 'success' : s === 'IN_PROGRESS' ? 'warning' : s === 'CANCELLED' ? 'info' : 'primary'
}
async function onCancel(row) {
  try {
    await ElMessageBox.confirm(`确认取消排程 #${row.id} (${row.prdOrderNo})?`, '取消排程', {
      type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消'
    })
  } catch { return }
  await mesApi.scheduleCancel(row.id)
  ElMessage.success('已取消')
  loadList(); loadGantt()
}

// ===== OEE =====
const oeeEquipmentId = ref(null)
const oee = ref({})
const oeeLoading = ref(false)
async function loadOee() {
  if (!oeeEquipmentId.value) { oee.value = {}; return }
  oeeLoading.value = true
  try {
    const r = await mesApi.oee(oeeEquipmentId.value)
    oee.value = r.data || {}
  } catch { oee.value = {} } finally { oeeLoading.value = false }
}

// ===== 新增排程 =====
const dialogVisible = ref(false)
const submitting = ref(false)
const form = ref(emptyForm())

function emptyForm() {
  return { prdOrderId: null, prdOrderNo: '', equipmentId: null, equipmentName: '', processName: '', planStart: '', planEnd: '', remark: '' }
}
function onOrderChange(id) {
  const o = orders.value.find(x => x.id === id)
  form.value.prdOrderNo = o ? o.billNo : ''
}
function onEquipChange(id) {
  const e = equipmentList.value.find(x => x.id === id)
  form.value.equipmentName = e ? (e.equipmentName || ('设备#' + e.id)) : ''
}
async function openAdd() {
  form.value = emptyForm()
  const [o, eq] = await Promise.all([
    prdOrderApi.page({ pageNum: 1, pageSize: 200 }),
    equipmentApi.listAll()
  ])
  orders.value = o.data?.records || []
  equipmentList.value = eq.data || []
  dialogVisible.value = true
}
async function onSubmit() {
  if (!form.value.equipmentId) { ElMessage.warning('请选择设备'); return }
  if (!form.value.planStart || !form.value.planEnd) { ElMessage.warning('请填写计划时段'); return }
  submitting.value = true
  try {
    await mesApi.scheduleSave({ ...form.value })
    ElMessage.success('排程已保存 (重叠时段后端已记冲突提示)')
    dialogVisible.value = false
    loadList(); loadGantt()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally { submitting.value = false }
}

function fmt(s) { return s ? String(s).replace('T', ' ').slice(0, 16) : '-' }

onMounted(async () => {
  loadList()
  // 设备下拉预载 (供甘特/OEE 选设备)
  try {
    const eq = await equipmentApi.listAll()
    equipmentList.value = eq.data || []
  } catch { equipmentList.value = [] }
})
</script>

<style scoped lang="scss">
.page { display: flex; flex-direction: column; gap: 14px; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.muted { color: var(--muted, #909399); font-size: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 12px; }

.gantt { border: 1px solid #edf1f4; border-radius: 8px; padding: 8px 12px; }
.gantt-timescale { display: grid; grid-template-columns: 180px repeat(25, 1fr); font-size: 10px; color: #b0b8c1; padding-left: 10px; }
.gantt-row { display: grid; grid-template-columns: 180px 1fr; align-items: center; padding: 8px 0; border-bottom: 1px solid #f5f7fa; }
.gantt-row:last-child { border-bottom: none; }
.gantt-label { font-size: 13px; padding-left: 10px; line-height: 1.3; }
.gantt-track { position: relative; height: 26px; background: repeating-linear-gradient(90deg, transparent, transparent 3.98%, #f0f3f7 3.98%, #f0f3f7 4%); }
.gantt-bar { position: absolute; top: 3px; height: 20px; border-radius: 4px; font-size: 10px; color: #fff; display: flex; align-items: center; padding: 0 6px; overflow: hidden; white-space: nowrap; }
.gantt-PLANNED { background: #409eff; }
.gantt-IN_PROGRESS { background: #e6a23c; }
.gantt-DONE { background: #67c23a; }
.gantt-empty { padding: 20px; text-align: center; color: var(--muted); font-size: 13px; }

.oee-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.oee-card { border: 1px solid #edf1f4; border-radius: 10px; padding: 16px; text-align: center; }
.oee-card b { display: block; font-size: 26px; font-weight: 700; color: var(--primary-color, #409eff); }
.oee-card small { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; }
</style>
