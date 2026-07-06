<template>
  <div>
    <!-- 系统名称配置 -->
    <div class="page-card" style="margin-bottom:16px">
      <div class="section-title">🏭 系统名称设置</div>
      <el-form label-width="140px" style="max-width:600px">
        <el-form-item label="系统名称">
          <el-input v-model="systemName" placeholder="请输入系统名称" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="saveSystemName(systemName)">保存</el-button>
          <span class="muted-tip">修改后刷新页面生效，将显示在左上角</span>
        </el-form-item>
      </el-form>
    </div>

    <!-- 服务器连接设置已迁移至登录页面 (登录前可配置, 更直观) -->

    <!-- 原有配置列表 -->
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="配置名称"><el-input v-model="query.configName" clearable /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.configType" clearable style="width:120px">
            <el-option label="系统" value="1" />
            <el-option label="打印" value="2" />
            <el-option label="公司" value="3" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <div class="tax-toggle">
        <el-switch
          v-model="taxSeparation"
          active-value="true"
          inactive-value="false"
          @change="onTaxSeparationChange"
          style="margin-right: 8px"
        />
        <span>价税分离模式</span>
        <span class="tax-tip">关闭时所有单价均为含税单价；开启后显示税率、税额等字段</span>
      </div>
      <div class="toolbar">
        <el-button type="primary" @click="onAdd">新增配置</el-button>
      </div>
      <el-table :data="filteredRecords" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="configName" label="配置名称" />
        <el-table-column prop="configKey" label="配置键" width="200" />
        <el-table-column prop="configValue" label="配置值" show-overflow-tooltip />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            {{ ({1:'系统',2:'打印',3:'公司'})[row.configType] }}
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑配置' : '新增配置'" width="500px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-form-item label="配置名称" required>
          <el-input v-model="form.configName" />
        </el-form-item>
        <el-form-item label="配置键" required>
          <el-input v-model="form.configKey" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="配置值" required>
          <el-input v-model="form.configValue" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.configType" style="width:100%">
            <el-option label="系统" :value="1" />
            <el-option label="打印" :value="2" />
            <el-option label="公司" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted, computed } from 'vue'
import { configApi } from '@/api/system'
import { useTaxSeparation } from '@/composables/useSystemConfig'
import { useSystemName } from '@/composables/useSystemName'
import { ElMessage, ElMessageBox } from 'element-plus'
const query = reactive({ pageNum: 1, pageSize: 20, configName: '', configType: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = ref({ id: null, configName: '', configKey: '', configValue: '', configType: 1, remark: '' })
const { taxSeparation, loadTaxSeparation, saveTaxSeparation } = useTaxSeparation()
const { systemName, loadSystemName, saveSystemName } = useSystemName()

// 列表中排除价税分离配置（由顶部开关独占）
const filteredRecords = computed(() => data.value.records.filter(r => r.configKey !== 'PRICE_TAX_SEPARATION'))

async function onTaxSeparationChange(val) {
  try {
    await saveTaxSeparation(val)
    ElMessage.success(val === 'true' ? '已开启价税分离模式' : '已关闭价税分离模式')
  } catch (e) { ElMessage.error(e.message || '保存失败') }
}

async function loadData() {
  loading.value = true
  try { data.value = (await configApi.page(query)).data } finally { loading.value = false }
  loadTaxSeparation()
}
function onAdd() {
  form.value = { id: null, configName: '', configKey: '', configValue: '', configType: 1, remark: '' }
  dialogVisible.value = true
}
function onEdit(row) {
  form.value = { ...row }
  dialogVisible.value = true
}
async function onSubmit() {
  if (!form.value.configName || !form.value.configKey) { ElMessage.warning('请填写名称和键'); return }
  submitting.value = true
  try {
    if (form.value.id) {
      await configApi.update(form.value)
    } else {
      await configApi.add(form.value)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally { submitting.value = false }
}
async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除 "${row.configName}"?`, '提示', { type: 'warning' })
  await configApi.delete(row.id)
  ElMessage.success('删除成功')
  loadData()
}
onMounted(() => { loadData(); loadTaxSeparation(); loadSystemName() })
</script>
<style scoped>
.toolbar { margin-bottom: 12px; }
.pager { margin-top: 12px; text-align: right; }
.tax-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #f0f9eb;
  border-radius: 4px;
  margin-bottom: 12px;
  font-size: 14px;
}
.tax-tip { color: #909399; font-size: 12px; }
.section-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; color: #333; }
.muted-tip { color: #909399; font-size: 12px; margin-left: 8px; }
</style>
