<template>
  <div>
    <div class="page-card">
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div class="section-title">🗓 设备维保计划</div>
      </div>

      <el-form :model="query" inline style="margin-bottom:12px">
        <el-form-item label="设备">
          <el-select v-model="query.deviceId" clearable filterable placeholder="全部设备" style="width:240px">
            <el-option v-for="eq in equipments" :key="eq.id" :label="`${eq.equipmentCode} ${eq.equipmentName}`" :value="eq.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="维保类型">
          <el-input v-model="query.maintType" clearable placeholder="日常保养/大修/定期" style="width:160px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.billStatus" clearable placeholder="全部" style="width:130px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已审核" value="CHECKED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="recordNo" label="记录号" width="170" />
        <el-table-column label="设备名称" min-width="140">
          <template #default="{ row }">{{ row.deviceName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="maintDate" label="日期" width="120">
          <template #default="{ row }">{{ row.maintDate || '-' }}</template>
        </el-table-column>
        <el-table-column prop="maintType" label="类型" width="110">
          <template #default="{ row }">{{ row.maintType || '-' }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="操作员" width="110">
          <template #default="{ row }">{{ row.operator || row.createByName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="result" label="结果" width="120">
          <template #default="{ row }">{{ row.result || '-' }}</template>
        </el-table-column>
        <el-table-column label="下次到期" width="130">
          <template #default="{ row }">
            <span :class="{ overdue: isOverdue(row) }">{{ row.nextDueDate || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.billStatus === 'CHECKED' ? 'success' : 'warning'" size="small">
              {{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus === 'DRAFT'" link type="success" size="small" @click="onCheck(row)">审核</el-button>
            <el-button v-if="row.billStatus === 'CHECKED'" link type="warning" size="small" @click="onUncheck(row)">反审核</el-button>
            <el-button v-if="row.billStatus === 'DRAFT'" link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!data.records.length && !loading" description="暂无维保记录" />
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" :current-page="query.pageNum" :page-size="query.pageSize"
        @current-change="load" @size-change="load" :page-sizes="[10, 20, 50, 100]" />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { maintainApi } from '@/api/workflow'
import { equipmentApi } from '@/api/equipment'

const loading = ref(false)
const data = ref({ records: [], total: 0 })
const query = reactive({
  pageNum: 1, pageSize: 20, deviceId: null, maintType: '', billStatus: ''
})
const equipments = ref([])

function isOverdue(row) {
  return row.nextDueDate && new Date(row.nextDueDate).getTime() < Date.now()
}

// 设备下拉: 拉全部设备 (pageSize=200), 静默兜底
async function loadEquipments() {
  try {
    const r = await equipmentApi.page({ pageSize: 200 })
    equipments.value = r?.data?.records || []
  } catch (e) {
    console.warn('[MaintainPlan] 设备列表加载失败:', e)
    equipments.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const p = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.deviceId) p.deviceId = query.deviceId
    if (query.maintType) p.maintType = query.maintType
    if (query.billStatus) p.billStatus = query.billStatus
    data.value = (await maintainApi.page(p)).data || { records: [], total: 0 }
  } catch (e) {
    // 与 OrderProgress.vue 一致, 列表加载失败静默兜底
    console.warn('[MaintainPlan] 列表加载失败:', e)
    data.value = { records: [], total: 0 }
  } finally {
    loading.value = false
  }
}

function onReset() {
  query.deviceId = null; query.maintType = ''; query.billStatus = ''; query.pageNum = 1
  load()
}

async function onCheck(row) {
  await ElMessageBox.confirm(`确认审核维保记录 ${row.recordNo}?`, '审核确认', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  try {
    await maintainApi.check(row.id)
    ElMessage.success('审核成功')
    load()
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '审核失败')
  }
}

async function onUncheck(row) {
  await ElMessageBox.confirm(`确认反审核维保记录 ${row.recordNo}? 状态将回退为草稿。`, '反审核确认', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  try {
    await maintainApi.uncheck(row.id)
    ElMessage.success('反审核成功')
    load()
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '反审核失败')
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除维保记录 ${row.recordNo}? 删除后不可恢复。`, '删除确认', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  try {
    await maintainApi.delete(row.id)
    ElMessage.success('删除成功')
    load()
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}

onMounted(() => {
  loadEquipments()
  load()
})
</script>

<style scoped>
.section-title { font-size:15px;font-weight:600;color:#333 }
.muted-tip { color:#909399;font-size:12px }
.pager { margin-top:12px; text-align:right; }
.overdue { color:#f56c6c;font-weight:bold }
</style>
