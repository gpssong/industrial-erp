<template>
  <div class="supplier-card" v-loading="loading">
    <div class="supplier-card-head">
      <div>
        <b>年度绩效评价</b>
        <small class="module-desc">5 维度加权评分（质量 35 / 交付 20 / 价格 15 / 服务 15 / 合规 15）→ 自动 A(≥90) / B(≥75) / C，并推算下次复评日</small>
      </div>
      <div class="supplier-head-actions">
        <el-select v-model="evalYear" clearable placeholder="全部年度" style="width: 130px" @change="load">
          <el-option v-for="y in years" :key="y" :label="y + ' 年'" :value="y" />
        </el-select>
        <el-input v-model="keyword" clearable style="width: 190px" placeholder="搜索供应商 / 等级" @keyup.enter="load">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button plain @click="load"><el-icon><Search /></el-icon>查询</el-button>
        <el-button plain @click="onExport"><el-icon><Download /></el-icon>导出</el-button>
        <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>新增评分</el-button>
      </div>
    </div>

    <el-table :data="rows" empty-text="暂无年度绩效评分 — 每年至少对食品接触原料供应商评价一次">
      <el-table-column label="供应商" min-width="170">
        <template #default="{ row }">
          <div style="font-weight:600;color:var(--text)">{{ row.supplierName || '-' }}</div>
          <div class="sub-cell">{{ row.supplierCode || '无编码' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="evalYear" label="年度" width="80" align="center" />
      <el-table-column label="质量 (35)" width="100" align="center"><template #default="{ row }">{{ num(row.qualityScore) }}</template></el-table-column>
      <el-table-column label="交付 (20)" width="100" align="center"><template #default="{ row }">{{ num(row.deliveryScore) }}</template></el-table-column>
      <el-table-column label="价格 (15)" width="100" align="center"><template #default="{ row }">{{ num(row.priceScore) }}</template></el-table-column>
      <el-table-column label="服务 (15)" width="100" align="center"><template #default="{ row }">{{ num(row.serviceScore) }}</template></el-table-column>
      <el-table-column label="合规 (15)" width="100" align="center"><template #default="{ row }">{{ num(row.complianceScore) }}</template></el-table-column>
      <el-table-column label="总分" width="90" align="center">
        <template #default="{ row }"><b>{{ num(row.totalScore) }}</b></template>
      </el-table-column>
      <el-table-column label="等级" width="80" align="center">
        <template #default="{ row }"><el-tag :type="gradeType(row.grade)" size="small" effect="dark">{{ row.grade || '—' }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="evalDate" label="评价日期" width="120" align="center" />
      <el-table-column label="下次复评" width="120" align="center">
        <template #default="{ row }">
          <span :class="{ 'warn-text': isDue(row.nextReviewDate) }">{{ row.nextReviewDate || '未排期' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑绩效评分' : '新增年度绩效评分'" width="660px" destroy-on-close>
      <el-form :model="form" label-width="112px">
        <el-form-item label="供应商" required>
          <el-select v-model="form.supplierId" filterable placeholder="选择供应商" style="width:100%" :disabled="!!form.id">
            <el-option v-for="s in suppliers" :key="s.id" :label="`${s.supplierName}${s.supplierCode ? ' · ' + s.supplierCode : ''}`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="评价年度" required>
              <el-input-number v-model="form.evalYear" :min="2000" :max="2100" :controls="false" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="评价日期"><el-date-picker v-model="form.evalDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="评价人"><el-input v-model="form.evaluator" placeholder="如 采购主管" /></el-form-item></el-col>
        </el-row>
        <el-divider content-position="left">维度评分（0-100）</el-divider>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="质量 · 35%"><el-input-number v-model="form.qualityScore" :min="0" :max="100" :precision="1" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="交付 · 20%"><el-input-number v-model="form.deliveryScore" :min="0" :max="100" :precision="1" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="价格 · 15%"><el-input-number v-model="form.priceScore" :min="0" :max="100" :precision="1" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="服务 · 15%"><el-input-number v-model="form.serviceScore" :min="0" :max="100" :precision="1" style="width:100%" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="合规/资质 · 15%"><el-input-number v-model="form.complianceScore" :min="0" :max="100" :precision="1" style="width:100%" /></el-form-item>
        <div class="score-preview">
          <span>加权总分 <b>{{ previewTotal }}</b></span>
          <el-tag :type="gradeType(previewGrade)" effect="dark">预计 {{ previewGrade }} 级</el-tag>
          <span class="sub-cell">A ≥ 90 · B ≥ 75 · C &lt; 75（最终以后端计算为准）</span>
        </div>
        <el-form-item label="下次复评">
          <el-date-picker v-model="form.nextReviewDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="留空自动: 评价日 +1 年" />
        </el-form-item>
        <el-form-item label="评价说明">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="来料批次合格率 / 交付及时率 / 质量异常与整改情况" />
        </el-form-item>
        <el-alert v-if="previewGrade === 'C'" type="warning" :closable="false" show-icon
          title="C 级供应商应限期整改, 必要时在名录「暂停供货」; 整改完成后重新评审" />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Download, Plus } from '@element-plus/icons-vue'
import { xkSupplierApi } from '@/api/xk'

const props = defineProps({ suppliers: { type: Array, default: () => [] } })
const emit = defineEmits(['changed'])

const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const evalYear = ref(null)
const dialogVisible = ref(false)
const saving = ref(false)

const nowYear = new Date().getFullYear()
const years = [nowYear, nowYear - 1, nowYear - 2, nowYear - 3, nowYear - 4]

const emptyForm = () => ({
  id: null, supplierId: null, evalYear: nowYear,
  qualityScore: 90, deliveryScore: 90, priceScore: 90, serviceScore: 90, complianceScore: 90,
  evalDate: today(), evaluator: '', nextReviewDate: '', remark: ''
})
const form = reactive(emptyForm())

function today() {
  const d = new Date(), p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
function num(v) { return Number(v) || 0 }
function gradeType(g) { return g === 'A' ? 'success' : (g === 'B' ? 'primary' : 'danger') }
function isDue(dateStr) {
  if (!dateStr) return false
  return (new Date(dateStr) - new Date()) / 86400000 <= 90
}

const previewTotal = computed(() => {
  const t = num(form.qualityScore) * 0.35 + num(form.deliveryScore) * 0.2
    + num(form.priceScore) * 0.15 + num(form.serviceScore) * 0.15 + num(form.complianceScore) * 0.15
  return Math.round(t * 100) / 100
})
const previewGrade = computed(() => (previewTotal.value >= 90 ? 'A' : (previewTotal.value >= 75 ? 'B' : 'C')))

async function load() {
  loading.value = true
  try {
    const r = await xkSupplierApi.perfList({ evalYear: evalYear.value || undefined, keyword: keyword.value, limit: 500 })
    rows.value = r?.data || []
  } catch (e) {
    ElMessage.error(e.message || '绩效记录加载失败')
    rows.value = []
  } finally { loading.value = false }
}

function onAdd() {
  Object.assign(form, emptyForm())
  if (props.suppliers.length === 1) form.supplierId = props.suppliers[0].id
  dialogVisible.value = true
}
function onEdit(row) { Object.assign(form, { ...row }); dialogVisible.value = true }

async function onSave() {
  if (!form.supplierId) return ElMessage.warning('请选择供应商')
  saving.value = true
  try {
    const r = await xkSupplierApi.perfSave({ ...form })
    const d = r?.data || {}
    ElMessage.success(`已保存: ${d.totalScore ?? previewTotal.value} 分 / ${d.grade ?? previewGrade.value} 级`)
    dialogVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { saving.value = false }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`删除 ${row.supplierName} ${row.evalYear} 年度绩效记录?`, '提示',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch (e) { return }
  try {
    await xkSupplierApi.perfDelete(row.id)
    ElMessage.success('已删除')
    await load()
    emit('changed')
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

function onExport() {
  const headers = ['供应商编码', '供应商名称', '年度', '质量(35)', '交付(20)', '价格(15)', '服务(15)', '合规(15)', '总分', '等级', '评价日期', '下次复评', '评价说明']
  const body = rows.value.map(r => [
    r.supplierCode || '', r.supplierName || '', r.evalYear || '',
    num(r.qualityScore), num(r.deliveryScore), num(r.priceScore), num(r.serviceScore), num(r.complianceScore),
    num(r.totalScore), r.grade || '', r.evalDate || '', r.nextReviewDate || '', (r.remark || '').replace(/[\t\r\n]+/g, ' ')
  ])
  const text = `${headers}\n${body.map(r => r.join('\t')).join('\n')}`
  const blob = new Blob(['\ufeff' + text], { type: 'text/tab-separated-values;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `XK年度绩效评价-${new Date().toISOString().slice(0, 10)}.tsv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${rows.value.length} 条`)
}

onMounted(load)
</script>

<style scoped>
.supplier-card { background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 16px 18px; box-shadow: var(--card-shadow); }
.supplier-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.supplier-card-head b { font-size: 15px; }
.module-desc { display: block; font-size: 12px; color: var(--muted); margin-top: 4px; }
.supplier-head-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.sub-cell { font-size: 12px; color: var(--muted); }
.warn-text { color: var(--warning); font-weight: 700; }
.score-preview {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  background: #f8fafc; border: 1px solid var(--line); border-radius: 8px;
  padding: 10px 12px; margin: 4px 0 14px;
}
.score-preview b { font-size: 18px; color: var(--primary-color); }
</style>
