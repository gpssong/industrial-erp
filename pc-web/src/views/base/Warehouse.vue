<template>
  <div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="left"><el-button type="primary" @click="onAdd">新增仓库</el-button></div>
      </div>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="warehouseCode" label="编码" width="120" />
        <el-table-column prop="warehouseName" label="仓库名称" />
        <el-table-column prop="warehouseType" label="类型" width="100" />
        <el-table-column prop="manager" label="负责人" width="100" />
        <el-table-column prop="phone" label="电话" width="120" />
        <el-table-column prop="address" label="地址" />
        <el-table-column label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault===1" type="success">是</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑' : '新增'" width="600px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="编码"><el-input v-model="form.warehouseCode" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.warehouseName" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.warehouseType" style="width:100%">
            <el-option label="原材料仓" value="RAW" />
            <el-option label="半成品仓" value="SEMI" />
            <el-option label="成品仓" value="FG" />
            <el-option label="普通仓" value="NORMAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人"><el-input v-model="form.manager" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="是否默认"><el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" /></el-form-item>
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
import { warehouseApi } from '@/api/base'
import { ElMessage, ElMessageBox } from 'element-plus'
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = reactive({ id: null, warehouseCode: '', warehouseName: '', warehouseType: 'NORMAL', manager: '', phone: '', address: '', isDefault: 0, status: 1 })
async function loadData() { loading.value = true; try { list.value = (await warehouseApi.list()).data } finally { loading.value = false } }
function onAdd() { Object.assign(form, { id: null, warehouseCode: '', warehouseName: '', isDefault: 0 }); dialogVisible.value = true }
function onEdit(row) { Object.assign(form, row); dialogVisible.value = true }
async function onDelete(row) {
  await ElMessageBox.confirm(`删除 ${row.warehouseName}?`, '提示', { type: 'warning' })
  await warehouseApi.delete(row.id); ElMessage.success('已删除'); loadData()
}
async function onSave() {
  if (form.id) await warehouseApi.update(form); else await warehouseApi.add(form)
  ElMessage.success('已保存'); dialogVisible.value = false; loadData()
}
onMounted(loadData)
</script>
