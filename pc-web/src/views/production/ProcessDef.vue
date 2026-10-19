<template>
  <div>
    <div class="page-card">
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div style="display:flex;gap:12px;align-items:center">
          <el-input v-model="productIdSearch" placeholder="按产品筛选 (输入产品ID)" style="width:220px" clearable
            @keyup.enter="onSearch" @clear="onSearch" />
          <el-button @click="onSearch">筛选</el-button>
        </div>
        <el-button type="primary" @click="openAdd">新增工序</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:12px">
        按 <b>产品/BOM</b> 定制工序链 (吹膜→印刷→复合→分切→制袋→包装)。标记「默认链」的工序在开生产单「开工」时
        自动展开成该单的工序实例, 工人才可扫码报工。未配工序定义的产品照常开工 (无工序可报工)。
      </el-alert>
      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="processNo" label="顺序" width="70" align="center" />
        <el-table-column prop="processName" label="工序名" width="140" />
        <el-table-column label="适用产品" width="180">
          <template #default="{ row }">{{ row.productId ? ('产品 ' + row.productId) : '通用(所有产品)' }}</template>
        </el-table-column>
        <el-table-column prop="workshop" label="车间/工位" width="140" />
        <el-table-column label="默认链" width="90" align="center">
          <template #default="{ row }"><el-tag v-if="row.isDefault" type="success" size="small">默认</el-tag><span v-else>-</span></template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑工序' : '新增工序'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="90px">
        <el-form-item label="工序名" required>
          <el-input v-model="form.processName" placeholder="吹膜 / 印刷 / 复合 / 分切 / 制袋 / 包装" />
        </el-form-item>
        <el-form-item label="顺序">
          <el-input-number v-model="form.processNo" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="适用产品">
          <el-input v-model.number="form.productId" placeholder="留空 = 通用工序 (所有产品)" style="width:240px" />
        </el-form-item>
        <el-form-item label="车间/工位">
          <el-input v-model="form.workshop" placeholder="吹膜车间 / 1号印刷机 ..." />
        </el-form-item>
        <el-form-item label="默认链">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
          <span style="margin-left:8px;color:#909399;font-size:12px">开单时自动展开成工序实例</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { mesApi } from '@/api/production'

const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const productIdSearch = ref('')
const dialogVisible = ref(false)
const form = reactive({ id: null, processNo: 1, processName: '', workshop: '', productId: null, isDefault: 0 })

async function load() {
  loading.value = true
  try {
    const pid = productIdSearch.value === '' ? null : Number(productIdSearch.value)
    const r = await mesApi.processDefList(pid)
    rows.value = r?.data || []
  } catch (e) {
    console.warn('[ProcessDef] 加载失败:', e)
  } finally {
    loading.value = false
  }
}
function onSearch() { load() }
function openAdd() {
  Object.assign(form, { id: null, processNo: 1, processName: '', workshop: '', productId: null, isDefault: 0 })
  dialogVisible.value = true
}
function openEdit(row) {
  Object.assign(form, {
    id: row.id, processNo: row.processNo ?? 1, processName: row.processName,
    workshop: row.workshop || '', productId: row.productId ?? null, isDefault: row.isDefault ?? 0
  })
  dialogVisible.value = true
}
async function onSave() {
  if (!form.processName) { ElMessage.warning('工序名必填'); return }
  saving.value = true
  try {
    await mesApi.processDefSave({ ...form, productId: form.productId || null })
    ElMessage.success('已保存')
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    saving.value = false
  }
}
async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除工序「${row.processName}」? 已展开的工序实例不受影响。`, '删除', { type: 'warning' }).catch(() => { throw new Error('cancel') })
  try {
    await mesApi.processDefDelete(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.search-bar { margin-bottom: 12px; }
</style>
