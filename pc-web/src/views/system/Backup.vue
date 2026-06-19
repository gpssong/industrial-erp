<template>
  <div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="doBackup" :loading="backingUp">立即备份</el-button>
        <el-button type="danger" @click="doFactoryReset" :loading="resetting">恢复出厂设置</el-button>
        <span class="tip">自动备份: 每天凌晨3点 | 恢复会覆盖当前数据，请谨慎操作</span>
      </div>
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="backupName" label="文件名" />
        <el-table-column prop="filePath" label="路径" show-overflow-tooltip />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ row.backupType === 1 ? '自动' : '手动' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">{{ row.status === 1 ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="备份时间" width="160" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="doRestore(row)" :loading="row._restoring">恢复</el-button>
            <el-button link type="danger" @click="doDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { backupApi } from '@/api/system'
import { ElMessage, ElMessageBox } from 'element-plus'

const query = reactive({ pageNum: 1, pageSize: 20 })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const backingUp = ref(false)
const resetting = ref(false)

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}
async function loadData() {
  loading.value = true
  try { data.value = (await backupApi.page(query)).data } finally { loading.value = false }
}
async function doBackup() {
  backingUp.value = true
  try {
    await backupApi.manual()
    ElMessage.success('备份已启动，请稍后刷新查看结果')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '备份失败')
  } finally { backingUp.value = false }
}
async function doRestore(row) {
  await ElMessageBox.confirm(
    `确定要恢复备份 "${row.backupName}" 吗？恢复将覆盖当前所有数据！`,
    '恢复确认',
    { type: 'warning', confirmButtonText: '确定恢复', cancelButtonText: '取消' }
  )
  row._restoring = true
  try {
    await backupApi.restore(row.id)
    ElMessage.success('恢复成功')
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  } finally {
    row._restoring = false
  }
}
async function doDelete(row) {
  await ElMessageBox.confirm(`确定删除备份 "${row.backupName}" 吗？`, '删除确认', { type: 'warning' })
  await backupApi.delete(row.id)
  ElMessage.success('删除成功')
  loadData()
}
async function doFactoryReset() {
  await ElMessageBox.confirm(
    '确定要恢复出厂设置吗？所有业务数据将被清空并重新初始化！此操作不可逆！',
    '恢复出厂设置',
    { type: 'warning', confirmButtonText: '确定恢复出厂', cancelButtonText: '取消' }
  )
  resetting.value = true
  try {
    await backupApi.factoryReset()
    ElMessage.success('恢复出厂设置成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  } finally {
    resetting.value = false
  }
}
onMounted(loadData)
</script>
<style scoped>
.toolbar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
.tip { color: #999; font-size: 12px; }
.pager { margin-top: 12px; text-align: right; }
</style>
