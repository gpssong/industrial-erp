<template>
  <div class="iqc-inline">
    <!-- 筛选栏 -->
    <div class="iqc-filter">
      <el-select v-model="query.result" placeholder="判定结果" clearable style="width: 140px">
        <el-option label="合格" value="PASS" />
        <el-option label="不合格" value="FAIL" />
        <el-option label="待定" value="HOLD" />
      </el-select>
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已审核" value="CHECKED" />
      </el-select>
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        style="width: 240px"
      />
      <el-button @click="loadList">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
    </div>

    <!-- 操作栏 -->
    <div class="iqc-toolbar">
      <div>
        <b>进货查验台账</b>
        <small>原料进货检验 · 与手机端 scan/in 数据互通</small>
      </div>
      <div>
        <el-button type="primary" @click="openCreate">新建检验</el-button>
        <el-button @click="loadList">刷新</el-button>
      </div>
    </div>

    <!-- 列表 -->
    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="billNo" label="检验报告号" width="140" />
      <el-table-column prop="inspectDate" label="检验日期" width="120">
        <template #default="{ row }">{{ row.inspectDate?.slice(0, 10) }}</template>
      </el-table-column>
      <el-table-column prop="sourceBillNo" label="采购订单号" width="140" />
      <el-table-column prop="supplierName" label="供应商" min-width="140" />
      <el-table-column prop="productName" label="商品" min-width="120" />
      <el-table-column prop="batchNo" label="批号" width="120" />
      <el-table-column prop="result" label="判定" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.result === 'PASS'" type="success">合格</el-tag>
          <el-tag v-else-if="row.result === 'FAIL'" type="danger">不合格</el-tag>
          <el-tag v-else-if="row.result === 'HOLD'" type="warning">待定</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.status === 'DRAFT'" type="info">草稿</el-tag>
          <el-tag v-else-if="row.status === 'CHECKED'" type="success">已审核</el-tag>
          <span v-else>{{ row.status }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="inspectorName" label="检验人" width="100" />
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="openEdit(row)">继续编辑</el-button>
          <el-button v-if="row.status === 'CHECKED'" link type="primary" @click="openDetail(row)">查看详情</el-button>
          <el-button v-if="row.status === 'DRAFT' && row.result === 'PASS'" link type="success" @click="openCheck(row)">审核</el-button>
          <el-button v-if="row.status === 'CHECKED' && user.isAdmin" link type="warning" @click="onResetToDraft(row)">重置为草稿</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > 0"
      :current-page="query.page"
      :page-size="query.pageSize"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="(p) => { query.page = p; loadList() }"
      style="margin-top: 16px; justify-content: flex-end"
    />

    <!-- 新建/编辑抽屉 -->
    <el-drawer v-model="editDrawer.visible" :title="editDrawer.isCreate ? '新建进货检验' : '编辑进货检验'" size="60%">
      <div v-loading="editDrawer.loading">
        <!-- 选订单 (仅新建时) -->
        <div v-if="editDrawer.isCreate" class="edit-section">
          <div class="edit-section-title">选择采购订单</div>
          <el-select v-model="editDrawer.orderId" placeholder="请选择采购订单" filterable style="width: 100%" @change="onOrderSelect">
            <el-option v-for="o in orderOptions" :key="o.id" :label="`${o.orderNo} - ${o.supplierName}`" :value="o.id" />
          </el-select>
        </div>

        <!-- 基本信息 -->
        <div v-if="editDrawer.iqcId" class="edit-section">
          <div class="edit-section-title">到货基本信息</div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="检验报告号">{{ editDrawer.head?.billNo }}</el-descriptions-item>
            <el-descriptions-item label="检验日期">{{ editDrawer.head?.inspectDate }}</el-descriptions-item>
            <el-descriptions-item label="供应商">{{ editDrawer.head?.supplierName }}</el-descriptions-item>
            <el-descriptions-item label="商品">{{ editDrawer.head?.productName }}</el-descriptions-item>
            <el-descriptions-item label="规格">{{ editDrawer.head?.spec }}</el-descriptions-item>
            <el-descriptions-item label="批号">{{ editDrawer.head?.batchNo }}</el-descriptions-item>
            <el-descriptions-item label="抽样数">{{ editDrawer.head?.sampleQty }}</el-descriptions-item>
            <el-descriptions-item label="检验人">{{ editDrawer.head?.inspectorName }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 检验项 -->
        <div v-if="editDrawer.iqcId" class="edit-section">
          <div class="edit-section-title">检验项目判定</div>
          <el-table :data="editDrawer.items" border size="small">
            <el-table-column prop="lineNo" label="#" width="50" />
            <el-table-column prop="itemName" label="检验项" min-width="120" />
            <el-table-column prop="requirement" label="标准要求" min-width="160" />
            <el-table-column label="判定" width="120">
              <template #default="{ row }">
                <el-radio-group v-model="row.passFlag">
                  <el-radio :label="1">合格</el-radio>
                  <el-radio :label="0">不合格</el-radio>
                  <el-radio :label="2">N/A</el-radio>
                </el-radio-group>
              </template>
            </el-table-column>
            <el-table-column prop="resultValue" label="实测值" width="100">
              <template #default="{ row }">
                <el-input v-model="row.resultValue" size="small" />
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 附件/照片 -->
        <div v-if="editDrawer.iqcId" class="edit-section">
          <div class="edit-section-title">附件/照片</div>
          <div v-if="!editDrawer.attachments?.length" class="empty-hint">暂无附件</div>
          <div v-else class="attach-grid">
            <div v-for="a in editDrawer.attachments" :key="a.id" class="attach-card">
              <el-link :href="a.fileUrl" target="_blank">{{ a.fileName }}</el-link>
              <el-tag v-if="a.verified" type="success" size="small">已核验</el-tag>
            </div>
          </div>
          <small style="color: #909399; margin-top: 8px; display: block">
            注：附件上传请在手机端 scan/in 完成，PC 端可查看已上传附件
          </small>
        </div>

        <!-- 操作按钮 -->
        <div v-if="editDrawer.iqcId" class="edit-actions">
          <el-button @click="editDrawer.visible = false">取消</el-button>
          <el-button type="primary" @click="saveDraft" :loading="editDrawer.saving">保存草稿</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailDrawer.visible" title="进货检验详情" size="50%">
      <div v-loading="detailDrawer.loading">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="检验报告号">{{ detailDrawer.head?.billNo }}</el-descriptions-item>
          <el-descriptions-item label="检验日期">{{ detailDrawer.head?.inspectDate }}</el-descriptions-item>
          <el-descriptions-item label="采购订单">{{ detailDrawer.head?.sourceBillNo }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detailDrawer.head?.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="商品">{{ detailDrawer.head?.productName }}</el-descriptions-item>
          <el-descriptions-item label="规格">{{ detailDrawer.head?.spec }}</el-descriptions-item>
          <el-descriptions-item label="批号">{{ detailDrawer.head?.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="抽样数">{{ detailDrawer.head?.sampleQty }}</el-descriptions-item>
          <el-descriptions-item label="检验人">{{ detailDrawer.head?.inspectorName }}</el-descriptions-item>
          <el-descriptions-item label="判定">
            <el-tag v-if="detailDrawer.head?.result === 'PASS'" type="success">合格</el-tag>
            <el-tag v-else-if="detailDrawer.head?.result === 'FAIL'" type="danger">不合格</el-tag>
            <el-tag v-else-if="detailDrawer.head?.result === 'HOLD'" type="warning">待定</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-section">
          <div class="detail-section-title">检验项</div>
          <el-table :data="detailDrawer.items" border size="small">
            <el-table-column prop="lineNo" label="#" width="50" />
            <el-table-column prop="itemName" label="检验项" min-width="120" />
            <el-table-column prop="requirement" label="标准要求" min-width="160" />
            <el-table-column label="判定" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.passFlag === 1" type="success">合格</el-tag>
                <el-tag v-else-if="row.passFlag === 0" type="danger">不合格</el-tag>
                <el-tag v-else-if="row.passFlag === 2">N/A</el-tag>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column prop="resultValue" label="实测值" width="100" />
          </el-table>
        </div>

        <div class="detail-section">
          <div class="detail-section-title">附件/照片</div>
          <div v-if="!detailDrawer.attachments?.length" class="empty-hint">暂无附件</div>
          <div v-else class="attach-grid">
            <div v-for="a in detailDrawer.attachments" :key="a.id" class="attach-card">
              <el-link :href="a.fileUrl" target="_blank">{{ a.fileName }}</el-link>
              <el-tag v-if="a.verified" type="success" size="small">已核验</el-tag>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 审核抽屉 -->
    <el-drawer v-model="checkDrawer.visible" title="审核进货检验" size="50%">
      <div v-loading="checkDrawer.loading">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="检验报告号">{{ checkDrawer.head?.billNo }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ checkDrawer.head?.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="商品">{{ checkDrawer.head?.productName }}</el-descriptions-item>
          <el-descriptions-item label="批号">{{ checkDrawer.head?.batchNo }}</el-descriptions-item>
        </el-descriptions>

        <div class="check-section">
          <div class="check-section-title">检验项复核</div>
          <el-table :data="checkDrawer.items" border size="small">
            <el-table-column prop="lineNo" label="#" width="50" />
            <el-table-column prop="itemName" label="检验项" min-width="120" />
            <el-table-column label="判定" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.passFlag === 1" type="success">合格</el-tag>
                <el-tag v-else-if="row.passFlag === 0" type="danger">不合格</el-tag>
                <el-tag v-else-if="row.passFlag === 2">N/A</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="check-section">
          <div class="check-section-title">综合判定</div>
          <el-radio-group v-model="checkDrawer.result">
            <el-radio label="PASS">合格</el-radio>
            <el-radio label="FAIL">不合格</el-radio>
            <el-radio label="HOLD">待定</el-radio>
          </el-radio-group>
        </div>

        <div class="check-actions">
          <el-button @click="checkDrawer.visible = false">取消</el-button>
          <el-button type="success" @click="submitCheck" :loading="checkDrawer.submitting">审核通过</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inspectionApi } from '@/api/quality'
import { purOrderApi } from '@/api/purchase'
import { useUserStore } from '@/store/user'

const user = useUserStore()

// 列表查询
const query = reactive({
  result: '',
  status: '',
  dateRange: [],
  page: 1,
  pageSize: 20
})
const list = ref([])
const total = ref(0)
const loading = ref(false)

async function loadList() {
  loading.value = true
  try {
    const params = {
      inspectionType: 'IQC',
      page: query.page,
      pageSize: query.pageSize
    }
    if (query.result) params.result = query.result
    if (query.status) params.status = query.status
    if (query.dateRange?.length === 2) {
      params.startDate = query.dateRange[0]
      params.endDate = query.dateRange[1]
    }
    const res = await inspectionApi.page(params)
    list.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    ElMessage.error('加载列表失败: ' + (e.message || e))
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.result = ''
  query.status = ''
  query.dateRange = []
  query.page = 1
  loadList()
}

// 新建/编辑抽屉
const editDrawer = reactive({
  visible: false,
  isCreate: false,
  loading: false,
  saving: false,
  iqcId: null,
  orderId: null,
  head: null,
  items: [],
  attachments: []
})
const orderOptions = ref([])

async function openCreate() {
  editDrawer.isCreate = true
  editDrawer.iqcId = null
  editDrawer.orderId = null
  editDrawer.head = null
  editDrawer.items = []
  editDrawer.attachments = []
  editDrawer.visible = true
  // 加载待检验订单
  try {
    const res = await purOrderApi.page({ page: 1, pageSize: 100, status: 'APPROVED' })
    orderOptions.value = res.data?.records || []
  } catch (e) {
    ElMessage.error('加载订单失败: ' + (e.message || e))
  }
}

async function onOrderSelect(orderId) {
  editDrawer.loading = true
  try {
    // 创建/复用 IQC 草稿
    const res = await inspectionApi.iqcCreateFromOrder(orderId)
    editDrawer.iqcId = res.data?.iqcId
    // 加载详情
    await loadIqcDetail()
  } catch (e) {
    ElMessage.error('创建检验单失败: ' + (e.message || e))
  } finally {
    editDrawer.loading = false
  }
}

async function openEdit(row) {
  editDrawer.isCreate = false
  editDrawer.iqcId = row.id
  editDrawer.orderId = row.sourceBillId
  editDrawer.visible = true
  editDrawer.loading = true
  try {
    await loadIqcDetail()
  } finally {
    editDrawer.loading = false
  }
}

async function loadIqcDetail() {
  const res = await inspectionApi.iqcDetailForOrder(editDrawer.iqcId)
  editDrawer.head = res.data?.head || res.data
  editDrawer.items = res.data?.items || []
  editDrawer.attachments = res.data?.attachments || []
}

async function saveDraft() {
  editDrawer.saving = true
  try {
    await inspectionApi.iqcSaveItems(editDrawer.iqcId, editDrawer.items)
    ElMessage.success('草稿已保存')
    editDrawer.visible = false
    loadList()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || e))
  } finally {
    editDrawer.saving = false
  }
}

// 详情抽屉
const detailDrawer = reactive({
  visible: false,
  loading: false,
  head: null,
  items: [],
  attachments: []
})

async function openDetail(row) {
  detailDrawer.head = null
  detailDrawer.items = []
  detailDrawer.attachments = []
  detailDrawer.visible = true
  detailDrawer.loading = true
  try {
    const res = await inspectionApi.iqcDetailForOrder(row.id)
    detailDrawer.head = res.data?.head || res.data
    detailDrawer.items = res.data?.items || []
    detailDrawer.attachments = res.data?.attachments || []
  } catch (e) {
    ElMessage.error('加载详情失败: ' + (e.message || e))
  } finally {
    detailDrawer.loading = false
  }
}

// 审核抽屉
const checkDrawer = reactive({
  visible: false,
  loading: false,
  submitting: false,
  iqcId: null,
  orderId: null,
  head: null,
  items: [],
  result: 'PASS'
})

async function openCheck(row) {
  checkDrawer.iqcId = row.id
  checkDrawer.orderId = row.sourceBillId
  checkDrawer.result = 'PASS'
  checkDrawer.visible = true
  checkDrawer.loading = true
  try {
    const res = await inspectionApi.iqcDetailForOrder(row.id)
    checkDrawer.head = res.data?.head || res.data
    checkDrawer.items = res.data?.items || []
  } catch (e) {
    ElMessage.error('加载检验单失败: ' + (e.message || e))
  } finally {
    checkDrawer.loading = false
  }
}

async function submitCheck() {
  checkDrawer.submitting = true
  try {
    await inspectionApi.check(checkDrawer.iqcId, {
      result: checkDrawer.result,
      reviewerName: user.userInfo?.nickName || user.userInfo?.username
    })
    ElMessage.success('审核完成')
    checkDrawer.visible = false
    loadList()
  } catch (e) {
    ElMessage.error('审核失败: ' + (e.message || e))
  } finally {
    checkDrawer.submitting = false
  }
}

/** v1.1.174 超管将已审核检验单重置为草稿 */
async function onResetToDraft(row) {
  try {
    await ElMessageBox.confirm(
      `确认将检验单 ${row.billNo} 重置为草稿？\n\n重置后可重新修改检验数据，批次联动也会回滚。`,
      '超管操作确认',
      { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' }
    )
    await inspectionApi.resetToDraft(row.id)
    ElMessage.success('已重置为草稿')
    loadList()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('重置失败: ' + (e.message || e))
    }
  }
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.iqc-inline {
  padding: 16px;
}
.iqc-filter {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  align-items: center;
}
.iqc-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 8px;
}
.iqc-toolbar small {
  color: #909399;
  margin-left: 8px;
}
.edit-section,
.detail-section,
.check-section {
  margin-top: 20px;
}
.edit-section-title,
.detail-section-title,
.check-section-title {
  font-weight: 600;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}
.edit-actions,
.check-actions {
  margin-top: 24px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.attach-list {
  margin-top: 8px;
}
.attach-item {
  padding: 4px 0;
}
.attach-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.attach-card {
  padding: 8px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.empty-hint {
  color: #909399;
  text-align: center;
  padding: 20px;
}
</style>
