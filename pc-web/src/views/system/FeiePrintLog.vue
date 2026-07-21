<template>
  <div>
    <div class="page-card">
      <div class="search-bar">
        <el-form :model="query" inline @submit.prevent>
          <el-form-item label="单据类型">
            <el-select v-model="query.bizType" placeholder="全部" clearable style="width:160px">
              <el-option v-for="t in BIZ_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="query.status" placeholder="全部" clearable style="width:120px">
              <el-option label="失败" :value="0" />
              <el-option label="已下发" :value="1" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="loadData"><el-icon><Search /></el-icon>查询</el-button>
            <el-button @click="onReset">重置</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="bizType" label="单据类型" width="140">
          <template #default="{ row }">{{ BIZ_TYPE_MAP[row.bizType] || row.bizType }}</template>
        </el-table-column>
        <el-table-column prop="billNo" label="单据号" width="180" show-overflow-tooltip />
        <el-table-column prop="deviceSn" label="设备SN" width="130" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '已下发' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="respCode" label="返回码" width="70" align="center" />
        <el-table-column prop="respMsg" label="返回信息" min-width="200" show-overflow-tooltip />
        <el-table-column prop="costMs" label="耗时(ms)" width="80" align="right" />
        <el-table-column prop="userName" label="操作人" width="100" />
        <el-table-column prop="clientIp" label="IP" width="130" />
        <el-table-column prop="createTime" label="时间" width="160" />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" background layout="total, prev, pager, next"
        :total="Number(data.total)" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="打印日志详情" width="600px" destroy-on-close>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="单据类型">{{ detail.bizType }}</el-descriptions-item>
        <el-descriptions-item label="单据号">{{ detail.billNo }}</el-descriptions-item>
        <el-descriptions-item label="设备SN">{{ detail.deviceSn }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="detail.status === 1 ? 'success' : 'danger'">
            {{ detail.status === 1 ? '已下发' : '失败' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="返回码">{{ detail.respCode }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ detail.costMs }}ms</el-descriptions-item>
        <el-descriptions-item label="操作人">{{ detail.userName }}</el-descriptions-item>
        <el-descriptions-item label="客户端IP">{{ detail.clientIp }}</el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">{{ detail.createTime }}</el-descriptions-item>
        <el-descriptions-item label="返回信息" :span="2">{{ detail.respMsg }}</el-descriptions-item>
        <el-descriptions-item label="内容哈希" :span="2">{{ detail.contentHash }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { feiePrintApi } from '@/api/feie'
import { ElMessage } from 'element-plus'

const BIZ_TYPE_OPTIONS = [
  { label: '生产加工单', value: 'PRD_ORDER' },
  { label: '销售出库单', value: 'SAL_DELIVERY' },
  { label: '销售退货单', value: 'SAL_RETURN' },
  { label: '采购入库单', value: 'PUR_RECEIPT' },
  { label: '采购退货单', value: 'PUR_RETURN' },
  { label: '库存盘点单', value: 'INV_CHECK' }
]
const BIZ_TYPE_MAP = {
  PRD_ORDER: '生产加工单', SAL_DELIVERY: '销售出库单', SAL_RETURN: '销售退货单',
  PUR_RECEIPT: '采购入库单', PUR_RETURN: '采购退货单', INV_CHECK: '库存盘点单'
}

const data = ref({ records: [], total: 0 })
const loading = ref(false)
const query = reactive({ pageNum: 1, pageSize: 20, bizType: '', status: null })
const detailVisible = ref(false)
const detail = ref({})

async function loadData() {
  loading.value = true
  try {
    const r = await feiePrintApi.logPage(query)
    data.value = r.data || { records: [], total: 0 }
  } catch (e) {
    ElMessage.error('加载失败: ' + (e.message || ''))
  } finally {
    loading.value = false
  }
}

function onReset() {
  query.bizType = ''
  query.status = null
  query.pageNum = 1
  loadData()
}

function showDetail(row) {
  detail.value = { ...row }
  detailVisible.value = true
}

onMounted(loadData)
</script>

<style scoped>
.pager { margin-top: 12px; text-align: right; }
.search-bar { margin-bottom: 12px; }
</style>