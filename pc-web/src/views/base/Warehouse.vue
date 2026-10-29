<template>
  <div>
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">基础资料 / 仓库</div>
        <h1 class="xk-title">仓库管理</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button type="primary" @click="onAdd">新增仓库</el-button>
      </div>
    </div>
    <div class="page-card">
      <div class="table-toolbar">
        <div class="left"><el-button type="primary" @click="onAdd">新增仓库</el-button></div>
      </div>
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="warehouseCode" label="编码" width="120" />
        <el-table-column prop="warehouseName" label="仓库名称" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag :type="row.warehouseType && row.warehouseType !== 'NORMAL' ? '' : 'info'" size="small">
              {{ WH_TYPE_LABELS[row.warehouseType] || '未分类' }}
            </el-tag>
          </template>
        </el-table-column>
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
          <el-select v-model="form.warehouseType" style="width: 100%">
            <el-option v-for="(label, key) in WH_TYPE_LABELS" :key="key" :label="label" :value="key" />
          </el-select>
          <div style="color:#909399;font-size:12px;line-height:1.5">入库/领料默认仓库按类型自动选中 (成品入库找 FG, 半成品入库找 SEMI, 领料找 RAW); 未分类时只能手选。</div>
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
// 与 sql/02 base_warehouse.warehouse_type 注释一致: NORMAL/RAW/FG/SEMI
// (PC/App 多处按类型自动选默认仓库, 见 FinishedIn.vue / SemiFinishedIn.vue / Order.vue)
const WH_TYPE_LABELS = { RAW: '原料仓', SEMI: '半成品仓', FG: '成品仓', NORMAL: '普通仓' }
const form = reactive({ id: null, warehouseCode: '', warehouseName: '', warehouseType: 'NORMAL', manager: '', phone: '', address: '', isDefault: 0, status: 1 })
async function loadData() { loading.value = true; try { list.value = (await warehouseApi.list()).data } finally { loading.value = false } }
function onAdd() { Object.assign(form, { id: null, warehouseCode: '', warehouseName: '', warehouseType: 'NORMAL', isDefault: 0 }); dialogVisible.value = true }
function onEdit(row) { Object.assign(form, row); if (!form.warehouseType) form.warehouseType = 'NORMAL'; dialogVisible.value = true }
async function onDelete(row) {
  await ElMessageBox.confirm(`删除 ${row.warehouseName}?`, '提示', { type: 'warning' })
  try {
    await warehouseApi.delete(row.id); ElMessage.success('已删除'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}
async function onSave() {
  try {
    if (form.id) await warehouseApi.update(form); else await warehouseApi.add(form)
    ElMessage.success('已保存'); dialogVisible.value = false; loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}
onMounted(loadData)
</script>
