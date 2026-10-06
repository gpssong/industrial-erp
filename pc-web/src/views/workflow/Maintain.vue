<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="设备名称"><el-input v-model="query.deviceName" clearable @keyup.enter="loadData" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.billStatus" clearable placeholder="全部" style="width:120px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已审核" value="CHECKED" />
          </el-select>
        </el-form-item>
        <el-form-item label="到期">
          <el-select v-model="query.overdue" clearable placeholder="全部" style="width:120px">
            <el-option label="已到期" :value="true" />
            <el-option label="未到期" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onReset">重置</el-button>
          <el-button v-if="userStore.hasPerm('work:maintain:add')" @click="onAdd" type="success"><el-icon><Plus /></el-icon>新增维保</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="recordNo" label="记录编号" width="170" />
        <el-table-column prop="deviceName" label="设备名称" min-width="140" />
        <el-table-column prop="maintDate" label="维保日期" width="120" />
        <el-table-column prop="maintType" label="维保类型" width="120">
          <template #default="{ row }">{{ row.maintType || '-' }}</template>
        </el-table-column>
        <el-table-column prop="operator" label="执行人" width="110">
          <template #default="{ row }">{{ row.operator || '-' }}</template>
        </el-table-column>
        <el-table-column prop="nextDueDate" label="下次到期" width="120">
          <template #default="{ row }">
            <span :class="{ 'overdue': isOverdue(row) }">{{ row.nextDueDate || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createByName" label="操作员" width="100">
          <template #default="{ row }">{{ row.createByName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.billStatus === 'CHECKED' ? 'success' : 'info'">{{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:maintain:edit')" link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:maintain:delete')" link type="danger" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.billStatus === 'DRAFT' && userStore.hasPerm('work:maintain:check')" link type="success" @click="onCheck(row)">审核</el-button>
            <el-button v-if="row.billStatus === 'CHECKED' && userStore.hasPerm('work:maintain:uncheck')" link type="warning" @click="onUncheck(row)">反审核</el-button>
            <el-button link type="primary" @click="onView(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        @current-change="loadData" @size-change="loadData" :page-sizes="[10, 20, 50, 100]" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑维保记录' : '新增维保记录'" width="900px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="设备名称" required><el-input v-model="form.deviceName" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="维保日期"><el-date-picker v-model="form.maintDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="维保类型"><el-input v-model="form.maintType" placeholder="日常保养/大修/定期" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="执行人"><el-input v-model="form.operator" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="下次到期"><el-date-picker v-model="form.nextDueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="结果"><el-input v-model="form.result" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="维保明细">
          <el-button @click="addLine" type="primary" plain size="small">添加行</el-button>
          <el-table :data="form.details" size="small" border style="margin-top:8px">
            <el-table-column label="维保项目" min-width="160"><template #default="{ row }"><el-input v-model="row.item" size="small" /></template></el-table-column>
            <el-table-column label="内容" min-width="200"><template #default="{ row }"><el-input v-model="row.content" size="small" /></template></el-table-column>
            <el-table-column label="结果" width="140"><template #default="{ row }"><el-input v-model="row.result" size="small" /></template></el-table-column>
            <el-table-column label="操作" width="60"><template #default="{ row, $index }"><el-button link type="danger" size="small" @click="form.details.splice($index, 1)">删</el-button></template></el-table-column>
          </el-table>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-if="!form.id || form.billStatus === 'DRAFT'" type="primary" @click="onSave" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="viewVisible" title="维保记录详情" width="820px" destroy-on-close>
      <el-descriptions :column="2" border v-if="view">
        <el-descriptions-item label="记录编号">{{ view.recordNo }}</el-descriptions-item>
        <el-descriptions-item label="设备名称">{{ view.deviceName }}</el-descriptions-item>
        <el-descriptions-item label="维保日期">{{ view.maintDate }}</el-descriptions-item>
        <el-descriptions-item label="维保类型">{{ view.maintType || '-' }}</el-descriptions-item>
        <el-descriptions-item label="执行人">{{ view.operator || '-' }}</el-descriptions-item>
        <el-descriptions-item label="下次到期">{{ view.nextDueDate || '-' }}</el-descriptions-item>
        <el-descriptions-item label="结果">{{ view.result || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ view.billStatus === 'CHECKED' ? '已审核' : '草稿' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="view.details || []" size="small" border style="margin-top:12px">
        <el-table-column prop="item" label="维保项目" min-width="160" />
        <el-table-column prop="content" label="内容" min-width="200" />
        <el-table-column prop="result" label="结果" width="140" />
      </el-table>
      <template #footer><el-button @click="viewVisible = false">关闭</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { maintainApi } from '@/api/workflow'
import { useUserStore } from '@/store/user'
import { Search, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const userStore = useUserStore()
const query = reactive({ pageNum: 1, pageSize: 20, deviceName: '', billStatus: '', overdue: null })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const viewVisible = ref(false)
const view = ref(null)
const form = reactive({ id: null, deviceName: '', maintDate: new Date().toISOString().substring(0, 10), maintType: '', operator: '', result: '', nextDueDate: '', remark: '', details: [], billStatus: 'DRAFT' })

function isOverdue(row) {
  return row.nextDueDate && new Date(row.nextDueDate).getTime() < Date.now()
}

async function loadData() {
  loading.value = true
  try {
    const params = { ...query }
    if (!params.deviceName) delete params.deviceName
    if (!params.billStatus) delete params.billStatus
    data.value = (await maintainApi.page(params)).data
  } finally { loading.value = false }
}

function onReset() {
  query.deviceName = ''; query.billStatus = ''; query.overdue = null; query.pageNum = 1
  loadData()
}

function onAdd() {
  Object.assign(form, { id: null, deviceName: '', maintDate: new Date().toISOString().substring(0, 10), maintType: '', operator: '', result: '', nextDueDate: '', remark: '', details: [], billStatus: 'DRAFT' })
  form.details.splice(0, form.details.length)
  dialogVisible.value = true
}

function addLine() {
  form.details.push({ item: '', content: '', result: '' })
}

async function onEdit(row) {
  const r = await maintainApi.detail(row.id)
  Object.assign(form, r.data)
  form.details = (r.data.details || []).map(d => ({ ...d }))
  dialogVisible.value = true
}

async function onSave() {
  if (!form.deviceName) return ElMessage.warning('请填写设备名称')
  submitting.value = true
  try {
    const payload = { ...form, details: form.details }
    if (form.id) {
      await maintainApi.update(payload); ElMessage.success('修改成功')
    } else {
      await maintainApi.add(payload); ElMessage.success('保存成功')
    }
    dialogVisible.value = false; loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

async function onView(row) {
  const r = await maintainApi.detail(row.id)
  view.value = r.data
  viewVisible.value = true
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除维保记录 ${row.recordNo}? 删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await maintainApi.delete(row.id)
    ElMessage.success('删除成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

async function onCheck(row) {
  try {
    await ElMessageBox.confirm(`确认审核维保记录 ${row.recordNo}?`, '审核确认', { type: 'warning' })
  } catch { return }
  try {
    await maintainApi.check(row.id); ElMessage.success('审核成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '审核失败') }
}

async function onUncheck(row) {
  try {
    await ElMessageBox.confirm(`确认反审核维保记录 ${row.recordNo}? 状态将回退为草稿。`, '反审核确认', { type: 'warning' })
  } catch { return }
  try {
    await maintainApi.uncheck(row.id); ElMessage.success('反审核成功'); loadData()
  } catch (e) { ElMessage.error(e.message || '反审核失败') }
}

onMounted(() => { loadData() })
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; } .overdue { color: #f56c6c; font-weight: bold; }</style>
