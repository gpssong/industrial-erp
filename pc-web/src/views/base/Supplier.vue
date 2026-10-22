<template>
  <div>
    <!-- Figma 页面标题 + XK 采购准入规则条 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">供应链 / 采购准入控制</div>
        <h1 class="xk-title">合格供应商管理</h1>
      </div>
      <div class="xk-heading-actions">
        <span class="xk-badge xk-badge-green">名录受控</span>
        <el-button type="success" @click="onAdd">新增供应商</el-button>
      </div>
    </div>
    <div class="supplier-rule">
      <el-icon><Promotion /></el-icon>
      <div>
        <strong>XK 采购准入控制</strong>
        <small>食品袋原料只能向"合格"且资质在有效期内的供应商采购；暂停供货的供应商不能创建采购订单。</small>
      </div>
    </div>
    <!-- v1.1.92+: 合格供应商 4 统计卡 (mockup supplier-standalone: 合格 / 暂停 / 名录 / 年度评价 A 级) -->
    <div class="supplier-stats">
      <div class="supplier-stat ss-green"><span class="ss-bar" /><div class="ss-label">合格供应商</div><div class="ss-num">{{ stats.qualified }}<small>家可采购</small></div></div>
      <div class="supplier-stat ss-red"><span class="ss-bar" /><div class="ss-label">暂停供货</div><div class="ss-num">{{ stats.suspended }}<small>家需恢复评审</small></div></div>
      <div class="supplier-stat ss-blue"><span class="ss-bar" /><div class="ss-label">名录总数</div><div class="ss-num">{{ stats.total }}<small>家受控</small></div></div>
      <div class="supplier-stat ss-amber"><span class="ss-bar" /><div class="ss-label">待资质年检</div><div class="ss-num">{{ stats.dueReview }}<small>家临期</small></div></div>
    </div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字"><el-input v-model="query.keyword" clearable /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="supplierCode" label="供应商编码" width="130">
          <template #default="{ row }"><span class="scope-cell" style="font-weight:700;color:var(--text)">{{ row.supplierCode || '-' }}</span></template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商名称" min-width="150">
          <template #default="{ row }">
            <div style="font-weight:600;color:var(--text)">{{ row.supplierName }}</div>
            <div class="assess-cell">{{ row.contactPerson ? `联系人 ${row.contactPerson}` : '—' }}{{ row.phone ? ' · ' + row.phone : '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="批准供货范围" min-width="150">
          <template #default="{ row }"><span class="scope-cell">{{ row.supplierType ? typeLabel(row.supplierType) : '通用原料' }}</span></template>
        </el-table-column>
        <el-table-column label="资质 / 评价" min-width="140">
          <template #default="{ row }"><span class="assess-cell">{{ row.remark || '资质有效 · 年度评价 A 级' }}</span></template>
        </el-table-column>
        <el-table-column label="食品资质" width="150" align="center">
          <template #default="{ row }">
            <span v-if="row.foodCertNo" class="scope-cell">{{ foodCertTypeLabel(row.foodCertType) }}<br/><small class="assess-cell">{{ row.foodCertNo }}{{ row.foodCertExp ? ' · 至 ' + row.foodCertExp : '' }}</small></span>
            <span v-else class="assess-cell">未登记</span>
          </template>
        </el-table-column>
        <el-table-column label="准入状态" width="110" align="center">
          <template #default="{ row }"><span class="rec-pill" :class="row.status === 1 ? 'ok' : 'danger'">{{ row.status === 1 ? '合格' : '暂停供货' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" :current-page="query.pageNum" :page-size="query.pageSize" @current-change="queryOnPageChange" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑' : '新增'" width="700px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="编码"><el-input v-model="form.supplierCode" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="名称"><el-input v-model="form.supplierName" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contactPerson" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="税率(%)"><el-input-number v-model="form.taxRate" :precision="2" :step-strictly="false" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="外协厂"><el-switch v-model="form.isOutsource" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="税号"><el-input v-model="form.taxNo" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="供货范围"><el-select v-model="form.supplierType"><el-option v-for="(v,k) in typeMap" :key="k" :label="v" :value="k" /></el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="准入状态"><el-switch v-model="form.status" active-text="合格" inactive-text="暂停供货" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="资质/评价"><el-input v-model="form.remark" placeholder="如：食品接触资料有效至 2026-12 · 年度评价 A 级" /></el-form-item>
        <!-- v1.1.100 食品资质 3 字段: 食品接触原料/食品相关产品生产许可证准入 (编号/有效期由用户填) -->
        <el-divider content-position="left">食品资质 (XK 食品接触原料准入)</el-divider>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="食品级证明编号"><el-input v-model="form.foodCertNo" placeholder="食品接触原料证明/食品相关产品生产许可证号" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="资质有效期至"><el-date-picker v-model="form.foodCertExp" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="可空" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="资质类型"><el-select v-model="form.foodCertType" clearable placeholder="可空">
            <el-option label="食品级原料" value="FOOD_GRADE_RAW" />
            <el-option label="食品相关产品生产许可" value="FOOD_PRODUCT_LICENSE" />
            <el-option label="食品接触级 (其他)" value="FOOD_CONTACT" />
          </el-select></el-form-item></el-col>
        </el-row>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { supplierApi } from '@/api/base'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion } from '@element-plus/icons-vue'

const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const form = reactive({
  id: null, supplierCode: '', supplierName: '',
  isOutsource: 0, contactPerson: '', phone: '', address: '',
  supplierType: 'NORMAL', taxRate: 13.00, status: 1, remark: '',
  foodCertNo: '', foodCertExp: null, foodCertType: null
})
const stats = computed(() => {
  const rows = data.value.records || []
  return {
    total: rows.length,
    qualified: rows.filter(r => r.status === 1).length,
    suspended: rows.filter(r => r.status !== 1).length,
    // 简化: 名录中无 remark 的视为待资质年检 (mockup 语义; 真实资质到期日需后端支持)
    dueReview: rows.filter(r => !r.remark && r.status === 1).length
  }
})
const typeMap = { NORMAL: '通用原料', MANUFACTURER: '自产供应商', AGENT: '经销代理商' }
function typeLabel(t) { return typeMap[t] || '通用原料' }
// v1.1.100 食品资质类型
const foodCertTypeMap = { FOOD_GRADE_RAW: '食品级原料', FOOD_PRODUCT_LICENSE: '食品相关产品生产许可', FOOD_CONTACT: '食品接触级' }
function foodCertTypeLabel(t) { return foodCertTypeMap[t] || '食品资质' }
async function loadData() { loading.value = true; try { data.value = (await supplierApi.page(query)).data } finally { loading.value = false } }
// v1.1.112 (R20): el-pagination handler 必须显式接参, 否则 pageNum 不更新, 点第 2 页无响应
function queryOnPageChange(p) { if (p === query.pageNum) return; query.pageNum = p; loadData() }
function queryOnSizeChange(s) { if (s === query.pageSize) return; query.pageSize = s; query.pageNum = 1; loadData() }
function onAdd() { Object.assign(form, { id: null, supplierCode: '', supplierName: '', supplierType: 'NORMAL', status: 1, taxRate: 13.00, isOutsource: 0, remark: '', foodCertNo: '', foodCertExp: null, foodCertType: null }); dialogVisible.value = true }
function onEdit(row) { Object.assign(form, row); dialogVisible.value = true }
async function onDelete(row) {
  await ElMessageBox.confirm(`删除 ${row.supplierName}?`, '提示', { type: 'warning' })
  try {
    await supplierApi.delete(row.id); ElMessage.success('已删除'); loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}
async function onSave() {
  try {
    if (form.id) await supplierApi.update(form); else await supplierApi.add(form)
    ElMessage.success('已保存'); dialogVisible.value = false; loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}
onMounted(loadData)
</script>
<style scoped>
/* v1.1.92+: 合格供应商模块 (Figma supplier-standalone) — 统计条 + 准入/资质 列 */
.supplier-stats {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 14px;
}
@media (max-width: 900px) { .supplier-stats { grid-template-columns: repeat(2, 1fr); } }
.supplier-stat {
  background: #fff; border: 1px solid var(--line); border-radius: 12px;
  padding: 14px 16px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
  position: relative; overflow: hidden;
}
.supplier-stat .ss-label { font-size: 12px; color: var(--muted); }
.supplier-stat .ss-num { font-size: 26px; font-weight: 800; color: var(--text); margin-top: 4px; line-height: 1; }
.supplier-stat .ss-num small { font-size: 12px; color: var(--muted); font-weight: 500; margin-left: 5px; }
.supplier-stat .ss-bar { position: absolute; left: 0; top: 0; bottom: 0; width: 4px; }
.supplier-stat.ss-green .ss-bar { background: var(--success); }
.supplier-stat.ss-red .ss-bar { background: var(--danger); }
.supplier-stat.ss-blue .ss-bar { background: var(--primary-color); }
.supplier-stat.ss-amber .ss-bar { background: var(--warning); }
.scope-cell { font-size: 12px; color: var(--text); }
.assess-cell { font-size: 12px; color: var(--muted); }
/* 准入状态 pill (对齐 mockup badge tone) */
.rec-pill {
  display: inline-block; padding: 3px 12px; border-radius: 999px;
  font-size: 11px; font-weight: 700; line-height: 1.4;
  &.ok { background: #e6f7ec; color: #1f8a4c; }
  &.danger { background: #ffeded; color: #d63b3b; }
}
.pager { margin-top: 12px; text-align: right; }
</style>
