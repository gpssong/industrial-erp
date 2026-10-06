<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键词"><el-input v-model="query.keyword" clearable placeholder="名称/联系人/电话/区域" @keyup.enter="loadData" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
          <el-button @click="onReset">重置</el-button>
          <el-button v-if="userStore.hasPerm('work:tenant:add')" @click="onAdd" type="success"><el-icon><Plus /></el-icon>新增租客</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="tenantCode" label="编号" width="140" />
        <el-table-column prop="tenantName" label="租客名称" min-width="160" />
        <el-table-column prop="contactName" label="联系人" width="120">
          <template #default="{ row }">{{ row.contactName || '-' }}</template>
        </el-table-column>
        <el-table-column prop="contactPhone" label="联系电话" width="140">
          <template #default="{ row }">{{ row.contactPhone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="area" label="区域/位置" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.area || '-' }}</template>
        </el-table-column>
        <el-table-column prop="deviceList" label="设备清单" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.deviceList || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }"><el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-if="userStore.hasPerm('work:tenant:edit')" link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button v-if="userStore.hasPerm('work:tenant:delete')" link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        @current-change="loadData" @size-change="loadData" :page-sizes="[10, 20, 50, 100]" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑租客' : '新增租客'" width="560px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="租客名称" required><el-input v-model="form.tenantName" /></el-form-item>
        <el-form-item label="编号"><el-input v-model="form.tenantCode" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contactName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="联系电话"><el-input v-model="form.contactPhone" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="区域/位置"><el-input v-model="form.area" /></el-form-item>
        <el-form-item label="设备清单"><el-input v-model="form.deviceList" placeholder="多台设备用逗号分隔" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { tenantApi } from '@/api/workflow'
import { useUserStore } from '@/store/user'
import { Search, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const userStore = useUserStore()
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({ id: null, tenantName: '', tenantCode: '', contactName: '', contactPhone: '', area: '', deviceList: '', remark: '', status: 1 })

async function loadData() {
  loading.value = true
  try {
    const params = { ...query }
    if (!params.keyword) delete params.keyword
    data.value = (await tenantApi.page(params)).data
  } finally { loading.value = false }
}

function onReset() {
  query.keyword = ''
  query.pageNum = 1
  loadData()
}

function onAdd() {
  Object.assign(form, { id: null, tenantName: '', tenantCode: '', contactName: '', contactPhone: '', area: '', deviceList: '', remark: '', status: 1 })
  dialogVisible.value = true
}

function onEdit(row) {
  Object.assign(form, { id: null, tenantName: '', tenantCode: '', contactName: '', contactPhone: '', area: '', deviceList: '', remark: '', status: 1 })
  Object.assign(form, row)
  dialogVisible.value = true
}

async function onSave() {
  if (!form.tenantName) return ElMessage.warning('请填写租客名称')
  submitting.value = true
  try {
    if (form.id) {
      await tenantApi.update(form)
      ElMessage.success('修改成功')
    } else {
      await tenantApi.add(form)
      ElMessage.success('保存成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`确认删除租客「${row.tenantName}」? 删除后不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await tenantApi.delete(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(() => { loadData() })
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
