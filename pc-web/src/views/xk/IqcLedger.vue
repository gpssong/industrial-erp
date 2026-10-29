<template>
  <div class="page">
    <div class="page-h">
      <h2>进货查验台账 (IQC)</h2>
      <div class="filters">
        <el-select v-model="query.result" placeholder="判定" clearable style="width: 140px">
          <el-option label="全部" value="" />
          <el-option label="PASS" value="PASS" />
          <el-option label="FAIL" value="FAIL" />
          <el-option label="HOLD" value="HOLD" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px">
          <el-option label="全部" value="" />
          <el-option label="DRAFT" value="DRAFT" />
          <el-option label="CHECKED" value="CHECKED" />
        </el-select>
        <el-button type="primary" @click="loadList">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>
    </div>

    <el-table :data="list" v-loading="loading" border>
      <el-table-column prop="billNo" label="检验报告号" width="180" />
      <el-table-column prop="billDate" label="检验日期" width="110" />
      <el-table-column prop="productName" label="商品" />
      <el-table-column prop="spec" label="规格" width="140" />
      <el-table-column prop="batchNo" label="批号" width="120" />
      <el-table-column prop="result" label="判定" width="90">
        <template #default="{ row }">
          <el-tag :type="row.result === 'PASS' ? 'success' : (row.result === 'FAIL' ? 'danger' : 'info')" size="small">
            {{ row.result || '—' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'CHECKED' ? 'success' : 'warning'" size="small">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="inspectorName" label="检验人" width="100" />
      <el-table-column prop="sampleQty" label="抽样数" width="90" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="onView(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      :page-sizes="[20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      @current-change="loadList"
      @size-change="loadList"
      style="margin-top: 16px; justify-content: flex-end"
    />

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="IQC 检验详情" width="900px" :destroy-on-close="true">
      <div v-if="detail.head" class="detail-h">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="报告号">{{ detail.head.billNo }}</el-descriptions-item>
          <el-descriptions-item label="检验日期">{{ detail.head.billDate }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.head.status === 'CHECKED' ? 'success' : 'warning'" size="small">
              {{ detail.head.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="商品">{{ detail.head.productName }}</el-descriptions-item>
          <el-descriptions-item label="规格">{{ detail.head.spec || '—' }}</el-descriptions-item>
          <el-descriptions-item label="批号">{{ detail.head.batchNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="判定">
            <el-tag :type="detail.head.result === 'PASS' ? 'success' : (detail.head.result === 'FAIL' ? 'danger' : 'info')" size="small">
              {{ detail.head.result }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="抽样数">{{ detail.head.sampleQty || '—' }}</el-descriptions-item>
          <el-descriptions-item label="检验人">{{ detail.head.inspectorName || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <h4 style="margin: 20px 0 12px">检验项明细</h4>
      <el-table :data="detail.items || []" border>
        <el-table-column prop="lineNo" label="行" width="60" />
        <el-table-column prop="itemName" label="项目" width="120" />
        <el-table-column prop="requirement" label="要求" />
        <el-table-column prop="method" label="方法" width="120" />
        <el-table-column prop="resultValue" label="结果" width="120" />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="合格" width="80">
          <template #default="{ row }">
            <el-tag :type="row.passFlag === 1 ? 'success' : 'danger'" size="small">
              {{ row.passFlag === 1 ? '✓' : '✗' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <h4 style="margin: 20px 0 12px">附件/照片</h4>
      <div class="att-grid">
        <el-card v-for="a in detail.attachments || []" :key="a.id" class="att-card" shadow="hover">
          <div class="att-type">{{ attachTypeName(a.attachType) }}</div>
          <div class="att-name">{{ a.attachName }}</div>
          <div v-if="isImage(a.fileUrl)" class="att-preview">
            <el-image :src="a.fileUrl" fit="cover" style="width: 100%; height: 100px" />
          </div>
          <div class="att-meta">
            <span :class="a.verified === 1 ? 'v-ok' : 'v-no'">{{ a.verified === 1 ? '✓ 已核验' : '未核验' }}</span>
            <el-button v-if="a.verified !== 1" link type="primary" size="small" @click="onVerify(a)">标记</el-button>
          </div>
        </el-card>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inspectionApi } from '@/api/quality'

const loading = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 20, result: '', status: 'CHECKED' })
const detailVisible = ref(false)
const detail = ref({})

const attachTypeName = (t) => {
  return { REPORT: '供应商报告', CONTRACT: '采购合同', INVOICE: '发票',
           LABEL_PHOTO: '原料标签', PACK_PHOTO: '包装照片', SAMPLE_PHOTO: '抽样照片', OTHER: '其他' }[t] || t
}
const isImage = (url) => /\.(png|jpg|jpeg|webp|gif)(\?|$)/i.test(url || '')

async function loadList() {
  loading.value = true
  try {
    const r = await inspectionApi.page({ ...query, inspectionType: 'IQC' })
    list.value = r.records || []
    total.value = Number(r.total) || 0
  } catch (e) {
    console.error('[IqcLedger] loadList failed:', e)
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, { pageNum: 1, pageSize: 20, result: '', status: 'CHECKED' })
  loadList()
}

async function onView(row) {
  try {
    const d = await inspectionApi.iqcDetail(row.id)
    detail.value = d || {}
    detailVisible.value = true
  } catch (e) {
    console.error('[IqcLedger] onView failed:', e)
    ElMessage.error('加载详情失败')
  }
}

async function onVerify(a) {
  try {
    await inspectionApi.iqcVerifyAttachment(a.id)
    ElMessage.success('已标记核验')
    // 刷新
    const d = await inspectionApi.iqcDetail(detail.value.head.id)
    detail.value = d || {}
  } catch (e) {
    console.error('[IqcLedger] onVerify failed:', e)
    ElMessage.error(e.msg || '核验失败')
  }
}

onMounted(loadList)
</script>

<style scoped>
.page { padding: 16px; }
.page-h { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-h h2 { margin: 0; }
.filters { display: flex; gap: 8px; }
.att-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 12px; }
.att-card { font-size: 13px; }
.att-type { color: #4096ff; font-weight: 600; margin-bottom: 4px; }
.att-name { color: #333; margin-bottom: 6px; word-break: break-all; }
.att-preview { margin: 6px 0; }
.att-meta { display: flex; justify-content: space-between; align-items: center; font-size: 12px; }
.v-ok { color: #52c41a; }
.v-no { color: #999; }
</style>
