<template>
  <div class="supplier-card" v-loading="loading">
    <div class="supplier-card-head">
      <div>
        <b>XK 准入评审记录</b>
        <small class="module-desc">初次准入 / 年度复评 / 专项评审逐次留档；结论「不通过」会自动阻断该供应商食品级采购订单</small>
      </div>
      <div class="supplier-head-actions">
        <el-input v-model="keyword" clearable style="width: 220px" placeholder="搜索供应商 / 评审人" @keyup.enter="load">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button plain @click="load"><el-icon><Search /></el-icon>查询</el-button>
        <el-button plain @click="onExport"><el-icon><Download /></el-icon>导出</el-button>
        <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>新增评审</el-button>
      </div>
    </div>

    <el-table :data="rows" empty-text="暂无准入评审记录 — 请为食品接触原料供应商补录初次准入评审">
      <el-table-column label="供应商" min-width="180">
        <template #default="{ row }">
          <div style="font-weight:600;color:var(--text)">{{ row.supplierName || '-' }}</div>
          <div class="sub-cell">{{ row.supplierCode || '无编码' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="评审类型" width="120" align="center">
        <template #default="{ row }"><el-tag size="small" effect="plain">{{ typeLabel(row.reviewType) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="reviewDate" label="评审日期" width="120" align="center" />
      <el-table-column prop="reviewer" label="评审人" width="140" />
      <el-table-column label="结论" width="120" align="center">
        <template #default="{ row }">
          <el-tag :type="resultType(row.result)" size="small" effect="dark">{{ resultLabel(row.result) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下次复评" width="130" align="center">
        <template #default="{ row }">
          <span :class="{ 'warn-text': isDue(row.nextReviewDate) }">{{ row.nextReviewDate || '未排期' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结论说明" min-width="200" show-overflow-tooltip>
        <template #default="{ row }"><span class="sub-cell">{{ row.conclusion || '—' }}</span></template>
      </el-table-column>
      <el-table-column label="附件" width="90" align="center">
        <template #default="{ row }">
          <a v-if="row.attachmentUrl" :href="row.attachmentUrl" target="_blank" class="sub-cell">查看</a>
          <span v-else class="sub-cell">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑评审记录' : '新增准入评审'" width="640px" destroy-on-close>
      <el-form :model="form" label-width="96px">
        <el-form-item label="供应商" required>
          <el-select v-model="form.supplierId" filterable placeholder="选择供应商" style="width:100%" :disabled="!!form.id">
            <el-option v-for="s in suppliers" :key="s.id" :label="`${s.supplierName}${s.supplierCode ? ' · ' + s.supplierCode : ''}`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="评审类型" required>
              <el-select v-model="form.reviewType" style="width:100%">
                <el-option label="初次准入" value="INITIAL" />
                <el-option label="年度复评" value="ANNUAL" />
                <el-option label="专项评审" value="SPECIAL" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="评审日期" required>
              <el-date-picker v-model="form.reviewDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="评审人" required><el-input v-model="form.reviewer" placeholder="可多人, 如 张工/李质检" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="评审结论" required>
              <el-select v-model="form.result" style="width:100%">
                <el-option label="通过" value="PASS" />
                <el-option label="有条件通过" value="CONDITIONAL" />
                <el-option label="不通过" value="FAIL" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="下次复评">
          <el-date-picker v-model="form.nextReviewDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="留空自动: 初次/年度 +1 年, 专项 +6 个月" />
        </el-form-item>
        <el-form-item label="结论说明">
          <el-input v-model="form.conclusion" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="现场审核情况 / 整改要求 / 有条件通过的前置条件" />
        </el-form-item>
        <el-form-item label="评审记录">
          <el-upload :show-file-list="false" :http-request="onUpload" accept=".pdf,.jpg,.jpeg,.png,.doc,.docx">
            <el-button size="small" plain :loading="uploading"><el-icon><Upload /></el-icon>上传评审表 / 现场照片</el-button>
          </el-upload>
          <div class="form-hint" v-if="form.attachmentUrl">已上传: <a :href="form.attachmentUrl" target="_blank">查看附件</a></div>
          <div class="form-hint" v-else>建议留存评审表扫描件, 与准入档案一并保存 ≥ 2 年</div>
        </el-form-item>
        <el-alert v-if="form.result === 'FAIL'" type="warning" :closable="false" show-icon
          title="结论为「不通过」时, 该供应商的食品级原料采购订单将被门禁拦截, 直至重新评审通过" />
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
import { Search, Download, Plus, Upload } from '@element-plus/icons-vue'
import { xkSupplierApi } from '@/api/xk'
import { uploadFile } from '@/api/workflow'

const props = defineProps({ suppliers: { type: Array, default: () => [] } })
const emit = defineEmits(['changed'])

const rows = ref([])
const loading = ref(false)
const keyword = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const uploading = ref(false)

const emptyForm = () => ({
  id: null, supplierId: null, reviewType: 'ANNUAL', reviewDate: today(),
  reviewer: '', result: 'PASS', nextReviewDate: '', conclusion: '', attachmentUrl: ''
})
const form = reactive(emptyForm())

function today() {
  const d = new Date(), p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
const typeMap = { INITIAL: '初次准入', ANNUAL: '年度复评', SPECIAL: '专项评审' }
function typeLabel(t) { return typeMap[t] || t || '年度复评' }
const resultMap = { PASS: '通过', CONDITIONAL: '有条件通过', FAIL: '不通过' }
function resultLabel(r) { return resultMap[r] || r || '—' }
function resultType(r) { return r === 'PASS' ? 'success' : (r === 'CONDITIONAL' ? 'warning' : 'danger') }
function isDue(dateStr) {
  if (!dateStr) return false
  const diff = (new Date(dateStr) - new Date()) / 86400000
  return diff <= 90
}

async function load() {
  loading.value = true
  try {
    const r = await xkSupplierApi.reviewList({ keyword: keyword.value, limit: 500 })
    rows.value = r?.data || []
  } catch (e) {
    ElMessage.error(e.message || '评审记录加载失败')
    rows.value = []
  } finally { loading.value = false }
}

function onAdd() {
  Object.assign(form, emptyForm())
  if (props.suppliers.length === 1) form.supplierId = props.suppliers[0].id
  dialogVisible.value = true
}
function onEdit(row) { Object.assign(form, { ...row }); dialogVisible.value = true }

async function onUpload(req) {
  uploading.value = true
  try { form.attachmentUrl = await uploadFile(req.file); ElMessage.success('附件已上传') }
  catch (e) { ElMessage.error(e.message || '上传失败') }
  finally { uploading.value = false }
}

async function onSave() {
  if (!form.supplierId) return ElMessage.warning('请选择供应商')
  if (!form.reviewer || !form.reviewer.trim()) return ElMessage.warning('请填写评审人')
  saving.value = true
  try {
    const r = await xkSupplierApi.reviewSave({ ...form })
    ElMessage.success('评审记录已保存' + (r?.data?.nextReviewDate ? `, 下次复评 ${r.data.nextReviewDate}` : ''))
    dialogVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { saving.value = false }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`删除 ${row.supplierName} 于 ${row.reviewDate} 的评审记录?`, '提示',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch (e) { return }
  try {
    await xkSupplierApi.reviewDelete(row.id)
    ElMessage.success('已删除')
    await load()
    emit('changed')
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

function onExport() {
  const headers = ['供应商编码', '供应商名称', '评审类型', '评审日期', '评审人', '结论', '下次复评', '结论说明']
  const body = rows.value.map(r => [
    r.supplierCode || '', r.supplierName || '', typeLabel(r.reviewType), r.reviewDate || '',
    r.reviewer || '', resultLabel(r.result), r.nextReviewDate || '', (r.conclusion || '').replace(/[\t\r\n]+/g, ' ')
  ])
  downloadTsv(`${headers}\n${body.map(r => r.join('\t')).join('\n')}`, 'XK准入评审记录')
}

function downloadTsv(text, name) {
  const blob = new Blob(['\ufeff' + text], { type: 'text/tab-separated-values;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${name}-${new Date().toISOString().slice(0, 10)}.tsv`
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
.form-hint { font-size: 12px; color: var(--muted); margin-top: 4px; }
</style>
