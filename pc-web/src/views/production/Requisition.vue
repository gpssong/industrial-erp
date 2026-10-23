<template>
  <div class="page">
    <!-- 页面标题 (对齐 Figma) -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">生产管理 / 领料记录</div>
        <h1 class="xk-title">领料记录</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="onExport"><el-icon><Download /></el-icon>导出</el-button>
      </div>
    </div>

    <!-- 搜索条 -->
    <div class="search-bar">
      <el-input
        v-model="query"
        placeholder="搜索领料单号 / 生产工单 / 领料人"
        clearable
        style="width: 320px"
        @keyup.enter="loadList"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" @click="loadList">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
    </div>

    <!-- 列表 -->
    <el-table :data="records" border stripe v-loading="loading" empty-text="暂无领料记录">
      <el-table-column type="index" width="50" label="#" />
      <el-table-column prop="billNo" label="领料单号" width="170" />
      <el-table-column prop="billDate" label="领料日期" width="120" />
      <el-table-column prop="orderNo" label="生产工单" width="170" />
      <el-table-column prop="workshop" label="领料车间" width="120" />
      <el-table-column prop="warehouseName" label="领料仓库" width="140" />
      <el-table-column prop="materialName" label="主要原料" min-width="160" />
      <el-table-column prop="qty" label="数量" width="120" align="right" />
      <el-table-column prop="creatorName" label="领料人" width="100" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.billStatus === 'CHECKED' ? 'success' : 'info'" size="small" effect="light">
            {{ row.billStatus === 'CHECKED' ? '已审核' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        background
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="onPageChange"
        @size-change="onPageSizeChange"
      />
    </div>

    <!-- 经营 AI 助手 -->
    <div class="ai-card">
      <div class="ai-left">
        <el-icon :size="22" color="var(--primary-color)"><ChatDotRound /></el-icon>
        <div>
          <b>经营 AI 助手</b>
          <small>可以询问「本月 LLDPE 领料合计」「哪些生产工单还未审核领料」</small>
        </div>
      </div>
      <el-button text type="primary" @click="router.push('/report/ai')">
        开始提问 <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Search, Download, ChatDotRound, ArrowRight
} from '@element-plus/icons-vue'
import { prdRequisitionApi } from '@/api/production'

const router = useRouter()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const query = ref('')

async function loadList() {
  loading.value = true
  try {
    const r = await prdRequisitionApi.page({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: query.value || undefined
    })
    if (r.code === 200) {
      records.value = r.data?.records || []
      total.value = r.data?.total || 0
    }
  } catch (e) {
    // 后端暂未连通时 (sql/56 未跑) 静默不弹错, 表格空态即可
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.value = ''
  pageNum.value = 1
  loadList()
}

function onPageChange() { loadList() }
function onPageSizeChange(s) { pageSize.value = s; pageNum.value = 1; loadList() }
function onExport() { ElMessage.info('导出功能接入中') }

onMounted(loadList)
</script>

<style scoped lang="scss">
.page { display: flex; flex-direction: column; gap: 16px; }
.search-bar { display: flex; gap: 8px; align-items: center; }
.pager { display: flex; justify-content: flex-end; }

.ai-card {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  background: linear-gradient(90deg, #eef6ff, #f8fbff);
  border: 1px solid #bfd8f2;
  border-radius: 14px;
  padding: 18px 20px;
}
.ai-left { display: flex; align-items: center; gap: 14px; }
.ai-left b { display: block; font-size: 14px; font-weight: 700; color: #245986; }
.ai-left small { display: block; font-size: 11px; color: #5c7690; margin-top: 3px; }
</style>