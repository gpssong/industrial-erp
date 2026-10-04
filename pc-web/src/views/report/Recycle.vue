<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键词">
          <el-input v-model="query.keyword" placeholder="单号 / 客户 / 供应商" style="width:220px"
                    clearable @keyup.enter="loadData" />
        </el-form-item>
        <el-form-item label="单据日期">
          <el-date-picker v-model="query.dateRange" type="daterange" value-format="YYYY-MM-DD"
                          style="width:240px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon style="margin:0 0 4px"
                title="回收站集中展示已删除 (草稿) 的业务单据。恢复 = 把单据翻回可用 (草稿)；彻底删除 = 物理删除、不可恢复，请谨慎。" />
    </div>

    <el-card style="margin-top:12px">
      <el-tabs v-model="activeTab">
        <el-tab-pane v-for="t in TYPES" :key="t.key" :name="t.key"
                     :label="t.label + (rowMap[t.key] && rowMap[t.key].length ? ' (' + rowMap[t.key].length + ')' : '')" />
      </el-tabs>

      <el-alert v-if="loadError" type="error" :closable="false" show-icon style="margin:8px 0 0"
                title="回收站加载失败: 当前账号缺少「报表查看 (report:view)」权限或服务异常。请联系管理员授予该权限, 或稍后重试。" />
      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column prop="billNo" label="单号" width="180" />
        <el-table-column prop="category" :label="catLabel" min-width="160" />
        <el-table-column prop="billDate" label="单据日期" width="120">
          <template #default="{ row }">{{ row.billDate || '-' }}</template>
        </el-table-column>
        <el-table-column prop="billStatus" label="删除时状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.billStatus === 'CHECKED' ? 'success' : 'info'" size="small">
              {{ statusLabel(row.billStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="删除时间" width="170">
          <template #default="{ row }">{{ row.updateTime || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onRestore(row)">恢复</el-button>
            <el-button link type="danger" @click="onPurge(row)">彻底删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无被删除的单据" :image-size="60" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportApi } from '@/api/report'

// 11 类单据 (key 与后端 RecycleType 一一对应)
const TYPES = [
  { key: 'pur_order', label: '采购订单' },
  { key: 'pur_receipt', label: '采购入库' },
  { key: 'pur_return', label: '采购退货' },
  { key: 'sal_order', label: '销售订单' },
  { key: 'sal_delivery', label: '销售出库' },
  { key: 'sal_return', label: '销售退货' },
  { key: 'prd_order', label: '生产加工单' },
  { key: 'prd_requisition', label: '生产领料' },
  { key: 'prd_bom', label: 'BOM清单' },
  { key: 'inv_check', label: '库存盘点' },
  { key: 'inv_transfer', label: '库存调拨' }
]

const activeTab = ref('pur_order')
const rowMap = ref({})       // { typeKey: [ {id,billNo,category,billDate,billStatus,updateTime} ] }
const loading = ref(false)
const loadError = ref(false) // 加载失败 (403 无权限 / 500 服务异常) 时, 显示提示而非静默空列表

const query = reactive({
  keyword: '',
  dateRange: null
})

const rows = computed(() => rowMap.value[activeTab.value] || [])
const catLabel = computed(() => {
  const t = TYPES.find(x => x.key === activeTab.value)
  const map = {
    pur_order: '供应商', pur_receipt: '供应商', pur_return: '供应商',
    sal_order: '客户', sal_delivery: '客户', sal_return: '客户',
    prd_order: '商品', prd_bom: '商品',
    prd_requisition: '来源生产单', inv_check: '仓库', inv_transfer: '调出仓库'
  }
  return map[t ? t.key : activeTab.value] || '类别'
})

function statusLabel(s) {
  const m = { DRAFT: '草稿', CHECKED: '已审核', FINISHED: '已完成', CANCELLED: '已作废' }
  return m[s] || s || '-'
}

async function loadData() {
  loading.value = true
  loadError.value = false
  try {
    const [s, e] = query.dateRange || []
    const r = await reportApi.recycleBin({
      keyword: query.keyword || undefined,
      startDate: s,
      endDate: e
    })
    rowMap.value = r.data || {}
  } catch (err) {
    // 拦截器已弹后端 msg; 这里再落个页面级提示, 避免 403/500 时整页静默空列表误导用户以为「没删过」
    loadError.value = true
    console.warn('[RecycleBin] 加载失败:', err)
  } finally {
    loading.value = false
  }
}

function onReset() {
  query.keyword = ''
  query.dateRange = null
  loadData()
}

async function onRestore(row) {
  try {
    await ElMessageBox.confirm(
      `确定恢复单据 ${row.billNo} 吗？恢复后将以「草稿」状态回到对应列表，可继续编辑/审核。`,
      '恢复单据', { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' })
  } catch { return }
  try {
    await reportApi.recycleRestore(activeTab.value, row.id)
    ElMessage.success('已恢复')
    loadData()
  } catch (err) {
    console.warn('[RecycleBin] 恢复失败:', err)
  }
}

async function onPurge(row) {
  try {
    await ElMessageBox.confirm(
      `确定彻底删除单据 ${row.billNo} 吗？此操作物理删除主表及明细，不可恢复。`,
      '彻底删除', { type: 'error', confirmButtonText: '彻底删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await reportApi.recyclePurge(activeTab.value, row.id)
    ElMessage.success('已彻底删除')
    loadData()
  } catch (err) {
    console.warn('[RecycleBin] 彻底删除失败:', err)
  }
}

onMounted(loadData)
</script>
