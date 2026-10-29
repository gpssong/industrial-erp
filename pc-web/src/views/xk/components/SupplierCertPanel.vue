<template>
  <div class="supplier-card" v-loading="loading">
    <div class="supplier-card-head">
      <div>
        <b>证照与检测报告</b>
        <small class="module-desc">生产许可 / GB 4806 检测报告 / 符合性声明分类留档；生产许可保存后自动回写供应商食品资质有效期（采购门禁口径）</small>
      </div>
      <div class="supplier-head-actions">
        <el-select v-model="certType" clearable placeholder="全部证照类型" style="width: 170px" @change="load">
          <el-option v-for="(v, k) in certTypeMap" :key="k" :label="v" :value="k" />
        </el-select>
        <el-input v-model="keyword" clearable style="width: 200px" placeholder="搜索供应商 / 证照编号" @keyup.enter="load">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button plain @click="load"><el-icon><Search /></el-icon>查询</el-button>
        <el-button plain @click="onExport"><el-icon><Download /></el-icon>导出</el-button>
        <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>登记证照</el-button>
      </div>
    </div>

    <el-table :data="rows" empty-text="暂无证照档案 — 请登记食品接触原料供应商的生产许可证与 GB 4806 检测报告">
      <el-table-column label="供应商" min-width="170">
        <template #default="{ row }">
          <div style="font-weight:600;color:var(--text)">{{ row.supplierName || '-' }}</div>
          <div class="sub-cell">{{ row.supplierCode || '无编码' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="证照类型" width="150">
        <template #default="{ row }"><el-tag size="small" effect="plain">{{ certTypeMap[row.certType] || '其他' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="名称 / 编号" min-width="220">
        <template #default="{ row }">
          <div>{{ row.certName || certTypeMap[row.certType] || '—' }}</div>
          <div class="sub-cell">{{ row.certNo }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="issuer" label="发证 / 检测机构" min-width="160" show-overflow-tooltip />
      <el-table-column label="有效期" width="190" align="center">
        <template #default="{ row }">
          <div class="sub-cell">{{ row.issueDate || '—' }} 至 {{ row.expireDate || '长期' }}</div>
          <el-tag :type="statusType(row)" size="small" effect="light">{{ statusLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="摘要" min-width="180" show-overflow-tooltip>
        <template #default="{ row }"><span class="sub-cell">{{ row.remark || '—' }}</span></template>
      </el-table-column>
      <el-table-column label="附件" width="90" align="center">
        <template #default="{ row }">
          <a v-if="row.fileUrl" :href="row.fileUrl" target="_blank" class="sub-cell">查看</a>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑证照' : '登记证照 / 检测报告'" width="640px" destroy-on-close>
      <el-form :model="form" label-width="104px">
        <el-form-item label="供应商" required>
          <el-select v-model="form.supplierId" filterable placeholder="选择供应商" style="width:100%" :disabled="!!form.id">
            <el-option v-for="s in suppliers" :key="s.id" :label="`${s.supplierName}${s.supplierCode ? ' · ' + s.supplierCode : ''}`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="证照类型" required>
              <el-select v-model="form.certType" style="width:100%">
                <el-option v-for="(v, k) in certTypeMap" :key="k" :label="v" :value="k" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="证照编号" required><el-input v-model="form.certNo" placeholder="许可证号 / 报告编号" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="证照名称"><el-input v-model="form.certName" placeholder="如: 全国工业产品生产许可证 / GB 4806.7 检测报告" /></el-form-item>
        <el-form-item label="发证机构"><el-input v-model="form.issuer" placeholder="如: 省市场监督管理局 / SGS" /></el-form-item>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="发证日期"><el-date-picker v-model="form.issueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="有效期至"><el-date-picker v-model="form.expireDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="单次报告可留空" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="摘要">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="检测项目与结论, 如: 高锰酸钾消耗量/重金属/脱色试验 全部符合 GB 4806.7-2016" />
        </el-form-item>
        <el-form-item label="扫描件">
          <el-upload :show-file-list="false" :http-request="onUpload" accept=".pdf,.jpg,.jpeg,.png">
            <el-button size="small" plain :loading="uploading"><el-icon><Upload /></el-icon>上传证照 PDF / 图片</el-button>
          </el-upload>
          <div class="form-hint" v-if="form.fileUrl">已上传: <a :href="form.fileUrl" target="_blank">查看附件</a></div>
          <div class="form-hint" v-else>XK16-204: 资质与检测报告须随批次可查, 建议扫描件长期留存</div>
        </el-form-item>
        <el-alert v-if="form.certType === 'PRODUCTION_LICENSE'" type="info" :closable="false" show-icon
          title="生产许可类证照保存后, 供应商的食品资质编号与有效期会自动同步, 采购订单/入库门禁按此校验" />
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
const certType = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const uploading = ref(false)

const certTypeMap = {
  PRODUCTION_LICENSE: '生产许可',
  GB4806_REPORT: 'GB 4806 检测报告',
  CONFORMITY_DECLARATION: '符合性声明',
  ISO22000: 'ISO 22000 认证',
  OTHER: '其他'
}

const emptyForm = () => ({
  id: null, supplierId: null, certType: 'PRODUCTION_LICENSE', certName: '', certNo: '',
  issuer: '', issueDate: '', expireDate: '', fileUrl: '', remark: ''
})
const form = reactive(emptyForm())

function daysLeft(dateStr) {
  if (!dateStr) return null
  return Math.round((new Date(dateStr) - new Date()) / 86400000)
}
function statusLabel(row) {
  const d = daysLeft(row.expireDate)
  if (d === null) return '长期有效'
  if (d < 0) return '已过期'
  if (d <= 90) return `临期 ${d} 天`
  return '有效'
}
function statusType(row) {
  const d = daysLeft(row.expireDate)
  if (d === null) return 'info'
  if (d < 0) return 'danger'
  if (d <= 90) return 'warning'
  return 'success'
}

async function load() {
  loading.value = true
  try {
    const r = await xkSupplierApi.certList({ certType: certType.value, keyword: keyword.value, limit: 500 })
    rows.value = r?.data || []
  } catch (e) {
    ElMessage.error(e.message || '证照加载失败')
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
  try { form.fileUrl = await uploadFile(req.file); ElMessage.success('附件已上传') }
  catch (e) { ElMessage.error(e.message || '上传失败') }
  finally { uploading.value = false }
}

async function onSave() {
  if (!form.supplierId) return ElMessage.warning('请选择供应商')
  if (!form.certNo || !form.certNo.trim()) return ElMessage.warning('请填写证照编号')
  saving.value = true
  try {
    await xkSupplierApi.certSave({ ...form })
    ElMessage.success('证照已保存')
    dialogVisible.value = false
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { saving.value = false }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(`删除 ${row.supplierName} 的「${certTypeMap[row.certType] || '证照'} ${row.certNo}」?`, '提示',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch (e) { return }
  try {
    await xkSupplierApi.certDelete(row.id)
    ElMessage.success('已删除')
    await load()
    emit('changed')
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

function onExport() {
  const headers = ['供应商编码', '供应商名称', '证照类型', '证照名称', '证照编号', '发证机构', '发证日期', '有效期至', '状态', '摘要']
  const body = rows.value.map(r => [
    r.supplierCode || '', r.supplierName || '', certTypeMap[r.certType] || '其他', r.certName || '',
    r.certNo || '', r.issuer || '', r.issueDate || '', r.expireDate || '', statusLabel(r),
    (r.remark || '').replace(/[\t\r\n]+/g, ' ')
  ])
  const text = `${headers}\n${body.map(r => r.join('\t')).join('\n')}`
  const blob = new Blob(['\ufeff' + text], { type: 'text/tab-separated-values;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `XK证照与检测报告-${new Date().toISOString().slice(0, 10)}.tsv`
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
.form-hint { font-size: 12px; color: var(--muted); margin-top: 4px; }
</style>
