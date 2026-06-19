<template>
  <div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="doBackup" :loading="backingUp">立即备份</el-button>
        <el-button type="danger" @click="showClearDialog">选择性清空数据</el-button>
        <el-button type="warning" @click="doFactoryReset" :loading="resetting">恢复出厂设置</el-button>
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

    <!-- 选择性清空数据对话框 -->
    <el-dialog v-model="clearDialogVisible" title="选择性清空数据" width="680px" destroy-on-close>
      <div class="clear-tip">勾选要清空的数据类别，清空后数据不可恢复，请谨慎操作！</div>
      <el-checkbox v-model="checkAll" @change="onCheckAllChange" class="check-all">全选</el-checkbox>
      <el-divider style="margin:10px 0" />
      <el-checkbox-group v-model="selectedTables">
        <el-row :gutter="16">
          <el-col :span="12" v-for="group in tableGroups" :key="group.label">
            <div class="group-title">{{ group.label }}</div>
            <el-checkbox
              v-for="item in group.items"
              :key="item.table"
              :value="item.table"
              :label="item.table"
              style="display:block;margin-bottom:4px"
            >{{ item.label }}</el-checkbox>
          </el-col>
        </el-row>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="clearDialogVisible=false">取消</el-button>
        <el-button type="danger" @click="doClearData" :loading="clearing" :disabled="selectedTables.length===0">
          确认清空 ({{ selectedTables.length }} 项)
        </el-button>
      </template>
    </el-dialog>
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
const clearing = ref(false)
const clearDialogVisible = ref(false)
const selectedTables = ref([])
const checkAll = ref(false)

// 按类别分组的数据表
const tableGroups = [
  {
    label: '📦 基础资料',
    items: [
      { label: '商品资料', table: 'base_product' },
      { label: '商品分类', table: 'base_product_category' },
      { label: '商品单位', table: 'base_product_unit' },
      { label: '价格等级', table: 'base_price_level' },
      { label: '客户资料', table: 'base_customer' },
      { label: '供应商资料', table: 'base_supplier' },
      { label: '计量单位', table: 'base_unit' },
      { label: '仓库资料', table: 'base_warehouse' },
      { label: '库区资料', table: 'base_warehouse_area' },
      { label: '库位资料', table: 'base_warehouse_location' },
    ]
  },
  {
    label: '🛒 采购管理',
    items: [
      { label: '采购询价单', table: 'pur_inquiry' },
      { label: '采购订单', table: 'pur_order' },
      { label: '采购入库单', table: 'pur_receipt' },
      { label: '采购退货单', table: 'pur_return' },
      { label: '付款记录', table: 'pur_payment' },
    ]
  },
  {
    label: '📋 销售管理',
    items: [
      { label: '销售报价单', table: 'sal_quotation' },
      { label: '销售订单', table: 'sal_order' },
      { label: '销售出库单', table: 'sal_delivery' },
      { label: '销售退货单', table: 'sal_return' },
      { label: '收款记录', table: 'sal_receipt' },
    ]
  },
  {
    label: '📊 库存管理',
    items: [
      { label: '库存台账', table: 'inv_stock' },
      { label: '库存账本', table: 'inv_ledger' },
      { label: '库存预警', table: 'inv_warning' },
      { label: '库存调拨', table: 'inv_transfer' },
      { label: '库存盘点', table: 'inv_check' },
      { label: '库存损益', table: 'inv_profit_loss' },
    ]
  },
  {
    label: '🔧 生产管理',
    items: [
      { label: 'BOM清单', table: 'prd_bom' },
      { label: '生产工单', table: 'prd_order' },
      { label: '生产领料', table: 'prd_requisition' },
      { label: '生产入库', table: 'prd_finished_in' },
      { label: '工序记录', table: 'prd_process' },
    ]
  },
  {
    label: '🔗 外协加工',
    items: [
      { label: '外协发料', table: 'out_issue' },
      { label: '外协收货', table: 'out_processing_in' },
      { label: '外协费用', table: 'out_process_fee' },
    ]
  },
  {
    label: '💰 财务管理',
    items: [
      { label: '应收应付', table: 'fin_arap' },
      { label: '现金流水', table: 'fin_cash_flow' },
      { label: '核销记录', table: 'fin_cash_writeoff' },
      { label: '往来对账', table: 'fin_reconciliation' },
      { label: '业务报表', table: 'rpt_daily_snapshot' },
    ]
  }
]

const allTables = tableGroups.flatMap(g => g.items.map(i => i.table))

function onCheckAllChange(val) {
  selectedTables.value = val ? [...allTables] : []
}

function showClearDialog() {
  selectedTables.value = []
  checkAll.value = false
  clearDialogVisible.value = true
}

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
async function doClearData() {
  if (selectedTables.value.length === 0) {
    ElMessage.warning('请先勾选要清空的数据类别')
    return
  }
  await ElMessageBox.confirm(
    `确定要清空 ${selectedTables.value.length} 项数据吗？此操作不可逆！`,
    '清空确认',
    { type: 'warning', confirmButtonText: '确认清空', cancelButtonText: '取消' }
  )
  clearing.value = true
  try {
    await backupApi.clearData(selectedTables.value)
    ElMessage.success('数据清空成功')
    clearDialogVisible.value = false
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  } finally {
    clearing.value = false
  }
}
onMounted(loadData)
</script>
<style scoped>
.toolbar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.tip { color: #999; font-size: 12px; }
.pager { margin-top: 12px; text-align: right; }
.clear-tip { color: #f56c6c; margin-bottom: 12px; font-size: 13px; }
.check-all { margin-bottom: 8px; }
.group-title { font-weight: 600; font-size: 13px; color: #333; margin-bottom: 6px; margin-top: 8px; }
</style>
