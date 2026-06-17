<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="left"><el-button type="primary" @click="onAdd">新增单位</el-button></div>
      </div>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="unitCode" label="编码" width="120" />
        <el-table-column prop="unitName" label="单位名称" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑' : '新增'" width="500px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="编码"><el-input v-model="form.unitCode" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.unitName" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { unitApi } from '@/api/base'
import { ElMessage, ElMessageBox } from 'element-plus'
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = reactive({ id: null, unitCode: '', unitName: '', status: 1 })
async function loadData() { loading.value = true; try { list.value = (await unitApi.list()).data } finally { loading.value = false } }
function onAdd() { Object.assign(form, { id: null, unitCode: '', unitName: '' }); dialogVisible.value = true }
function onEdit(row) { Object.assign(form, row); dialogVisible.value = true }
async function onDelete(row) {
  await ElMessageBox.confirm(`删除 ${row.unitName}?`, '提示', { type: 'warning' })
  await unitApi.delete(row.id); ElMessage.success('已删除'); loadData()
}
async function onSave() {
  if (form.id) await unitApi.update(form); else await unitApi.add(form)
  ElMessage.success('已保存'); dialogVisible.value = false; loadData()
}
onMounted(loadData)
</script>
