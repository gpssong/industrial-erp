<template>
  <div>
    <div class="page-card">
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div class="section-title">🖥 设备主数据</div>
        <el-button type="primary" @click="openAdd">新增设备</el-button>
      </div>

      <el-form :model="query" inline style="margin-bottom:12px">
        <el-form-item label="类型">
          <el-select v-model="query.type" clearable placeholder="全部" style="width:150px">
            <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="车间">
          <el-select v-model="query.workshop" clearable filterable allow-create placeholder="车间/工位" style="width:180px">
            <el-option v-for="w in workshopOptions" :key="w" :label="w" :value="w" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width:130px">
            <el-option label="在用" value="ACTIVE" />
            <el-option label="维保中" value="MAINT" />
            <el-option label="闲置" value="IDLE" />
            <el-option label="报废" value="SCRAP" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" clearable placeholder="设备名称" style="width:180px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="list" border stripe v-loading="loading" row-key="id" :expand-row-keys="expandedKeys" @expand-change="onExpand">
        <!-- 展开行: 该设备绑定的工序 -->
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding:8px 16px;background:#fafbfc">
              <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                <b>🔧 绑定工序 — {{ (row._processes || []).length }} 道</b>
                <el-button link type="primary" size="small" @click="openBind(row)">重新绑定工序</el-button>
              </div>
              <el-table :data="row._processes || []" size="small" border style="max-width:560px">
                <el-table-column prop="sortNo" label="顺序" width="60" align="center" />
                <el-table-column prop="processName" label="工序名" min-width="140" />
                <el-table-column prop="remark" label="备注" min-width="140">
                  <template #default="{ r }">{{ r.remark || '-' }}</template>
                </el-table-column>
              </el-table>
              <div v-if="!row._processes" class="muted-tip" style="padding:8px">加载中…</div>
              <div v-else-if="!row._processes.length" class="muted-tip" style="padding:8px">未绑定任何工序</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column type="index" width="50" />
        <el-table-column prop="equipmentCode" label="编号" width="130" />
        <el-table-column prop="equipmentName" label="名称" min-width="140" />
        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.equipmentType)" size="small">{{ typeLabel(row.equipmentType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="型号" width="120">
          <template #default="{ row }">{{ row.model || '-' }}</template>
        </el-table-column>
        <el-table-column prop="manufacturer" label="厂家" width="120">
          <template #default="{ row }">{{ row.manufacturer || '-' }}</template>
        </el-table-column>
        <el-table-column prop="installDate" label="安装日期" width="110">
          <template #default="{ row }">{{ row.installDate || '-' }}</template>
        </el-table-column>
        <el-table-column prop="workshop" label="车间" width="110">
          <template #default="{ row }">{{ row.workshop || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下次维保" width="110">
          <template #default="{ row }">{{ row.nextMaintDate || '-' }}</template>
        </el-table-column>
        <el-table-column label="二维码" width="90" align="center">
          <template #default="{ row }">
            <span v-if="row.qrcode" style="color:#67c23a;font-size:12px">已生成</span>
            <span v-else class="muted-tip">无</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无设备" />
    </div>

    <!-- 新增 / 编辑 对话框 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑设备' : '新增设备'" width="640px" destroy-on-close>
      <el-form :model="form" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="设备编号" required><el-input v-model="form.equipmentCode" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="设备名称" required><el-input v-model="form.equipmentName" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="类型">
              <el-select v-model="form.equipmentType" clearable style="width:100%">
                <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="型号"><el-input v-model="form.model" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="厂家"><el-input v-model="form.manufacturer" /></el-form-item></el-col>
          <el-col :span="12">
            <el-form-item label="安装日期">
              <el-date-picker v-model="form.installDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="安装位置"><el-input v-model="form.location" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="车间"><el-input v-model="form.workshop" placeholder="吹膜车间 / 1号印刷机 ..." /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width:100%">
                <el-option label="在用" value="ACTIVE" />
                <el-option label="维保中" value="MAINT" />
                <el-option label="闲置" value="IDLE" />
                <el-option label="报废" value="SCRAP" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="上次保养">
              <el-date-picker v-model="form.lastMaintDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="下次维保">
              <el-date-picker v-model="form.nextMaintDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="二维码">
          <div style="display:flex;gap:8px;width:100%">
            <el-input v-model="form.qrcode" placeholder="QR 内容 (如 QR:&lt;设备编号&gt;)" />
            <el-button @click="genQr">生成二维码</el-button>
          </div>
          <div class="muted-tip" style="margin-top:4px">简化版: 生成 "QR:" + 设备编号, 可手动改; 未来接 qrcode 库渲染图片</div>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重新绑定工序 对话框 -->
    <el-dialog v-model="bindVisible" title="重新绑定工序" width="480px" destroy-on-close>
      <div class="muted-tip" style="margin-bottom:8px">勾选该设备承担的工序, 保存后旧绑定全部清除再重插。</div>
      <el-checkbox-group v-model="bindNames">
        <div v-for="p in bindableProcessNames" :key="p" style="padding:4px 0">
          <el-checkbox :value="p">{{ p }}</el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="bindVisible = false">取消</el-button>
        <el-button type="primary" :loading="bindLoading" @click="onBind">保存绑定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { equipmentApi, equipmentProcessApi } from '@/api/equipment'
import { mesApi } from '@/api/production'

const TYPE_OPTIONS = [
  { value: 'FILM_BLOWING', label: '吹膜' },
  { value: 'SIDE_SEAL', label: '侧封' },
  { value: 'BAG_MAKING', label: '制袋' },
  { value: 'PRINTING', label: '印刷' },
  { value: 'LAMINATING', label: '复合' },
  { value: 'SLITTING', label: '分切' }
]

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const query = reactive({ type: '', workshop: '', status: '', keyword: '' })
const workshopOptions = ref([])
const expandedKeys = ref([])

// 新增/编辑
const dialogVisible = ref(false)
const emptyForm = () => ({
  id: null, equipmentCode: '', equipmentName: '', equipmentType: '', model: '',
  manufacturer: '', installDate: '', location: '', workshop: '', status: 'ACTIVE',
  qrcode: '', lastMaintDate: '', nextMaintDate: '', remark: ''
})
const form = reactive(emptyForm())

// 绑定工序
const bindVisible = ref(false)
const bindRow = ref(null)
const bindableProcessNames = ref([])
const bindNames = ref([])
const bindLoading = ref(false)

function typeTag(t) {
  return { FILM_BLOWING: 'primary', SIDE_SEAL: 'success', BAG_MAKING: 'warning', PRINTING: 'danger', LAMINATING: 'info', SLITTING: 'success' }[t] || 'info'
}
function typeLabel(t) {
  const found = TYPE_OPTIONS.find(x => x.value === t)
  return found ? found.label : (t || '-')
}
function statusTag(s) {
  return { ACTIVE: 'success', MAINT: 'warning', IDLE: 'info', SCRAP: 'danger' }[s] || 'info'
}
function statusLabel(s) {
  return { ACTIVE: '在用', MAINT: '维保中', IDLE: '闲置', SCRAP: '报废' }[s] || s
}

// 后端 page 只支持 workshop/type 两个过滤, 关键字在本地按名称过滤
async function load() {
  loading.value = true
  try {
    const p = {}
    if (query.type) p.type = query.type
    if (query.workshop) p.workshop = query.workshop
    p.pageSize = 200
    const r = await equipmentApi.page(p)
    let rows = r?.data?.records || []
    if (query.status) rows = rows.filter(x => x.status === query.status)
    if (query.keyword) {
      const kw = String(query.keyword).trim().toLowerCase()
      rows = rows.filter(x => (x.equipmentName || '').toLowerCase().includes(kw))
    }
    list.value = rows
    workshopOptions.value = Array.from(new Set(rows.map(x => x.workshop).filter(Boolean)))
  } catch (e) {
    console.warn('[Equipment] 列表加载失败:', e)
    list.value = []
  } finally {
    loading.value = false
  }
}

function onReset() {
  query.type = ''; query.workshop = ''; query.status = ''; query.keyword = ''
  load()
}

// 展开行: 懒加载绑定工序
async function onExpand(row, expanded) {
  expandedKeys.value = expanded.map(x => x.id)
  if (!expanded.some(x => x.id === row.id)) return
  if (row._processes) return // 已加载
  row._processes = null
  try {
    const r = await equipmentProcessApi.list(row.id)
    row._processes = r?.data || []
  } catch (e) {
    console.warn('[Equipment] 工序绑定加载失败:', e)
    row._processes = []
  }
}

function openAdd() {
  Object.assign(form, emptyForm())
  dialogVisible.value = true
}

async function openEdit(row) {
  Object.assign(form, emptyForm())
  try {
    const r = await equipmentApi.detail(row.id)
    const d = r?.data || {}
    Object.assign(form, {
      id: d.id, equipmentCode: d.equipmentCode, equipmentName: d.equipmentName,
      equipmentType: d.equipmentType || '', model: d.model || '', manufacturer: d.manufacturer || '',
      installDate: d.installDate || '', location: d.location || '', workshop: d.workshop || '',
      status: d.status || 'ACTIVE', qrcode: d.qrcode || '', lastMaintDate: d.lastMaintDate || '',
      nextMaintDate: d.nextMaintDate || '', remark: d.remark || ''
    })
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '加载设备失败')
    return
  }
  dialogVisible.value = true
}

function genQr() {
  const code = form.equipmentCode || form.equipmentName || ''
  if (!code) { ElMessage.warning('请先填写设备编号或名称'); return }
  form.qrcode = 'QR:' + code
}

async function onSave() {
  if (!form.equipmentCode || !form.equipmentName) {
    ElMessage.warning('设备编号/名称必填'); return
  }
  saving.value = true
  try {
    const payload = { ...form }
    if (!form.installDate) payload.installDate = null
    if (!form.lastMaintDate) payload.lastMaintDate = null
    if (!form.nextMaintDate) payload.nextMaintDate = null
    await equipmentApi.save(payload)
    ElMessage.success('已保存')
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除设备「${row.equipmentName}」? 将级联清除其工序绑定。`, '删除', { type: 'warning' })
    .catch(() => { throw new Error('cancel') })
  try {
    await equipmentApi.delete(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '删除失败')
  }
}

async function openBind(row) {
  bindRow.value = row
  bindNames.value = (row._processes || []).map(x => x.processName).filter(Boolean)
  // 拉全部工序名 (通用 + 按设备所在车间) 供勾选
  try {
    const r = await mesApi.processDefList(null)
    bindableProcessNames.value = Array.from(new Set((r?.data || []).map(x => x.processName).filter(Boolean)))
  } catch (e) {
    console.warn('[Equipment] 工序名加载失败:', e)
    bindableProcessNames.value = bindNames.value.slice()
  }
  bindVisible.value = true
}

async function onBind() {
  bindLoading.value = true
  try {
    await equipmentProcessApi.bind({
      equipmentId: bindRow.value.id,
      processNames: bindNames.value
    })
    ElMessage.success('已重新绑定')
    bindVisible.value = false
    // 刷新该行的绑定展示
    delete bindRow.value._processes
    try {
      const r = await equipmentProcessApi.list(bindRow.value.id)
      bindRow.value._processes = r?.data || []
    } catch (e) { /* 静默 */ }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '绑定失败')
  } finally {
    bindLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.section-title { font-size:15px;font-weight:600;color:#333 }
.muted-tip { color:#909399;font-size:12px }
</style>
