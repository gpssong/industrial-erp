<template>
  <div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd">新增部门</el-button>
      </div>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="deptName" label="部门名称" />
        <el-table-column prop="deptCode" label="编码" width="120" />
        <el-table-column prop="leader" label="负责人" width="100" />
        <el-table-column prop="phone" label="电话" width="130" />
        <el-table-column prop="email" label="邮箱" width="180" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status===1?'success':'info'" size="small">{{ row.status===1?'正常':'停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sortNo" label="排序" width="70" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑部门' : '新增部门'" width="500px" destroy-on-close>
      <el-form :model="form" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="上级部门" v-if="deptTree.length">
              <el-cascader v-model="form.parentId" :options="deptTree" :props="{ checkStrictly: true, emitPath: false, value: 'id', label: 'deptName' }" clearable style="width:100%" placeholder="最顶级" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="部门编码" required>
              <el-input v-model="form.deptCode" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="部门名称" required>
              <el-input v-model="form.deptName" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排序">
              <el-input-number v-model="form.sortNo" :min="0" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="负责人">
              <el-input v-model="form.leader" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="电话">
              <el-input v-model="form.phone" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">正常</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { deptApi } from '@/api/system'
import { ElMessage, ElMessageBox } from 'element-plus'

const list = ref([])
const deptTree = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({
  id: null, parentId: null, deptCode: '', deptName: '', leader: '', phone: '', email: '',
  sortNo: 0, status: 1, remark: ''
})

async function loadData() {
  loading.value = true
  try {
    list.value = (await deptApi.list()).data
    deptTree.value = (await deptApi.tree()).data
  } finally { loading.value = false }
}
function onAdd() {
  Object.assign(form, { id: null, parentId: null, deptCode: '', deptName: '', leader: '', phone: '', email: '', sortNo: 0, status: 1, remark: '' })
  dialogVisible.value = true
}
function onEdit(row) {
  Object.assign(form, { id: row.id, parentId: row.parentId, deptCode: row.deptCode, deptName: row.deptName, leader: row.leader || '', phone: row.phone || '', email: row.email || '', sortNo: row.sortNo || 0, status: row.status, remark: row.remark || '' })
  dialogVisible.value = true
}
async function onSubmit() {
  if (!form.deptName || !form.deptCode) { ElMessage.warning('请填写名称和编码'); return }
  submitting.value = true
  try {
    if (form.id) await deptApi.update(form)
    else await deptApi.add(form)
    ElMessage.success('保存成功'); dialogVisible.value = false; loadData()
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { submitting.value = false }
}
async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除部门 "${row.deptName}" 吗？`, '删除确认', { type: 'warning' })
  await deptApi.delete(row.id)
  ElMessage.success('删除成功'); loadData()
}
onMounted(loadData)
</script>
<style scoped>
.toolbar { margin-bottom: 12px; }
</style>
