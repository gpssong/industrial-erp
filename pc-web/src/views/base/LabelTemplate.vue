<template>
  <div>
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">基础资料 / 标签</div>
        <h1 class="xk-title">标签模板</h1>
        <p class="xk-sub">坐标单位是「点」：80×60mm @203dpi = 640×480 点。保存时校验字段名与越界，不会把错误留到打印那一刻。</p>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="onNew">新建模板</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存模板</el-button>
      </div>
    </div>

    <div class="page-card">
      <div class="table-toolbar">
        <div class="left">
          <el-select v-model="queryBizType" clearable placeholder="全部类型" style="width: 160px" @change="loadList">
            <el-option v-for="t in BIZ_TYPES" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
          <el-select v-model="current.id" placeholder="选择模板" clearable style="width: 260px" @change="onPick">
            <el-option v-for="t in list" :key="t.id" :label="`${t.templateName} (${t.templateCode})`" :value="t.id" />
          </el-select>
        </div>
        <div class="right">
          <el-button link type="primary" :disabled="!current.id" @click="onSetDefault">设为默认</el-button>
          <el-button link type="danger" :disabled="!current.id" @click="onDelete">删除</el-button>
        </div>
      </div>

      <el-form :model="current" label-width="110px" inline class="geo-form">
        <el-form-item label="模板编码"><el-input v-model="current.templateCode" style="width: 180px" /></el-form-item>
        <el-form-item label="模板名称"><el-input v-model="current.templateName" style="width: 180px" /></el-form-item>
        <el-form-item label="标签类型">
          <el-select v-model="current.bizType" style="width: 140px" @change="onBizChange">
            <el-option v-for="t in BIZ_TYPES" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="宽(mm)"><el-input-number v-model="current.widthMm" :min="20" :max="200" /></el-form-item>
        <el-form-item label="高(mm)"><el-input-number v-model="current.heightMm" :min="20" :max="200" /></el-form-item>
        <el-form-item label="间隙(mm)"><el-input-number v-model="current.gapMm" :min="0" :max="20" /></el-form-item>
        <el-form-item label="dpi">
          <el-select v-model="current.dpi" style="width: 100px"><el-option :value="203" label="203" /><el-option :value="300" label="300" /></el-select>
        </el-form-item>
        <el-form-item label="出纸方向">
          <el-select v-model="current.direction" style="width: 120px"><el-option value="NORMAL" /><el-option value="REVERSE" /></el-select>
        </el-form-item>
        <el-form-item label="份数"><el-input-number v-model="current.copies" :min="1" :max="20" /></el-form-item>
        <el-form-item label="画布"><span class="canvas-hint">{{ widthDots }} × {{ heightDots }} 点</span></el-form-item>
      </el-form>

      <el-alert v-if="issues.length" type="error" :closable="false" show-icon
                title="保存被拦下，下列元素需要修正" >
        <ul class="issue-list"><li v-for="(m, i) in issues" :key="i">{{ m }}</li></ul>
      </el-alert>

      <div class="el-bar">
        <el-button size="small" @click="addRow('text')">+ 文本</el-button>
        <el-button size="small" @click="addRow('multiline')">+ 多行</el-button>
        <el-button size="small" @click="addRow('barcode')">+ 条码</el-button>
        <el-button size="small" @click="addRow('qrcode')">+ 二维码</el-button>
        <el-button size="small" @click="addRow('line')">+ 分割线</el-button>
        <el-button size="small" @click="addRow('rect')">+ 矩形框</el-button>
      </div>

      <el-table :data="rows" border stripe size="small" v-loading="loading">
        <el-table-column type="index" width="46" label="#" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }"><el-select v-model="row.type" size="small">
            <el-option v-for="t in ELEMENT_TYPES" :key="t" :label="t" :value="t" /></el-select></template>
        </el-table-column>
        <el-table-column label="x" width="90">
          <template #default="{ row }"><el-input-number v-model="row.x" :min="0" :controls="false" size="small" /></template>
        </el-table-column>
        <el-table-column label="y" width="90">
          <template #default="{ row }"><el-input-number v-model="row.y" :min="0" :controls="false" size="small" /></template>
        </el-table-column>
        <el-table-column label="字段" min-width="170">
          <template #default="{ row }">
            <el-select v-model="row.field" clearable filterable size="small" placeholder="取上下文字段">
              <el-option v-for="f in fields" :key="f.name" :label="`${f.label} ${f.name}`" :value="f.name" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="或字面文本" min-width="150">
          <template #default="{ row }"><el-input v-model="row.text" size="small" /></template>
        </el-table-column>
        <el-table-column label="字号" width="90">
          <template #default="{ row }"><el-input-number v-model="row.size" :min="1" :controls="false" size="small" /></template>
        </el-table-column>
        <el-table-column label="宽" width="80">
          <template #default="{ row }"><el-input-number v-model="row.w" :min="0" :controls="false" size="small" /></template>
        </el-table-column>
        <el-table-column label="高" width="80">
          <template #default="{ row }"><el-input-number v-model="row.h" :min="0" :controls="false" size="small" /></template>
        </el-table-column>
        <el-table-column label="对齐" width="100">
          <template #default="{ row }"><el-select v-model="row.align" size="small">
            <el-option value="LEFT" /><el-option value="CENTER" /><el-option value="RIGHT" /></el-select></template>
        </el-table-column>
        <el-table-column label="粗体" width="70">
          <template #default="{ row }"><el-checkbox v-model="row.bold" /></template>
        </el-table-column>
        <el-table-column label="必填" width="70">
          <template #default="{ row }"><el-checkbox v-model="row.required" /></template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ $index }">
            <el-button link size="small" :disabled="$index === 0" @click="moveRow($index, -1)">上移</el-button>
            <el-button link type="danger" size="small" @click="rows.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>

      <p class="foot-hint">越界判定：字宽按真机实测的点阵（中文 TSS24.BF2 = 24 点/字、ASCII 字体"2" = 12 点/字，再乘按字号算的放大倍率，docs/34）。文本超宽会自动拆行往下压，所以拦的是「右侧放不下一个字符」与「拆完越过下边界」；line/rect 按声明宽高查；条码/二维码宽度不拦（TSPL 按内容自适应）。屏显预览不忠实打印机中文字库，最终以实纸为准。</p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { labelApi, labelTemplateApi } from '@/api/base'

const BIZ_TYPES = [
  { code: 'FG_ROLL', name: '成品卷标' },
  { code: 'RAW_PALLET', name: '原料垛标' },
  { code: 'CARTON', name: '箱标' },
  { code: 'CERT', name: '合格证(本期不出)' }
]
const ELEMENT_TYPES = ['text', 'multiline', 'barcode', 'qrcode', 'line', 'rect']

const route = useRoute()
const list = ref([])
const rows = ref([])
const fields = ref([])
const issues = ref([])
const loading = ref(false)
const saving = ref(false)
const queryBizType = ref('')
const current = reactive({
  id: null, templateCode: '', templateName: '', bizType: 'FG_ROLL',
  widthMm: 80, heightMm: 60, gapMm: 3, dpi: 203, direction: 'NORMAL', copies: 1,
  status: 1, remark: '', isDefault: 0
})

/** 换算与服务端同源：203dpi 的真值是 203.2（8 点/毫米），否则 80mm 会算成 639 点、比后端画布少 1 点 */
const trueDpi = (dpi) => (dpi === 203 ? 203.2 : dpi)
const dots = (mm, dpi) => Math.floor((mm * trueDpi(dpi)) / 25.4)
const widthDots = computed(() => dots(current.widthMm || 0, current.dpi || 203))
const heightDots = computed(() => dots(current.heightMm || 0, current.dpi || 203))

async function loadList() {
  loading.value = true
  try {
    list.value = (await labelTemplateApi.list(queryBizType.value ? { bizType: queryBizType.value } : {})).data || []
  } catch (e) {
    ElMessage.warning(e.message || '模板列表加载失败（可能无权限）')
    list.value = []
  } finally { loading.value = false }
}

async function loadFields(bizType) {
  try {
    fields.value = (await labelApi.fields(bizType)).data || []
  } catch (e) {
    fields.value = []
    ElMessage.warning(e.message || '字段名单加载失败')
  }
}

async function onPick(id) {
  if (!id) return
  const r = await labelTemplateApi.detail(id)
  const t = r.data || {}
  Object.assign(current, {
    id: t.id, templateCode: t.templateCode, templateName: t.templateName, bizType: t.bizType,
    widthMm: t.widthMm, heightMm: t.heightMm, gapMm: t.gapMm, dpi: t.dpi,
    direction: t.direction, copies: t.copies, status: t.status, remark: t.remark, isDefault: t.isDefault
  })
  rows.value = parseElements(t.elements)
  issues.value = []
  await loadFields(t.bizType)
}

function parseElements(json) {
  try {
    const arr = JSON.parse(json || '[]')
    return arr.map(e => ({
      type: e.type || 'text', x: e.x || 0, y: e.y || 0, text: e.text || '', field: e.field || '',
      size: e.size || 24, bold: !!e.bold, align: e.align || 'LEFT',
      w: e.w || 0, h: e.h || 0, required: !!e.required
    }))
  } catch { return [] }
}

function onNew() {
  Object.assign(current, {
    id: null, templateCode: '', templateName: '', bizType: 'FG_ROLL',
    widthMm: 80, heightMm: 60, gapMm: 3, dpi: 203, direction: 'NORMAL', copies: 1, status: 1, remark: '', isDefault: 0
  })
  rows.value = []
  issues.value = []
  loadFields('FG_ROLL')
}

async function onBizChange() { await loadFields(current.bizType) }

function addRow(type) {
  rows.value.push({
    type, x: 20, y: rows.value.length ? (rows.value[rows.value.length - 1].y + 40) : 20,
    text: '', field: '', size: type === 'qrcode' ? 7 : (type === 'barcode' ? 60 : 24),
    bold: false, align: 'LEFT', w: type === 'line' ? 600 : (type === 'rect' ? 620 : 0), h: type === 'line' ? 2 : 0,
    required: false
  })
}

function moveRow(i, delta) {
  const j = i + delta
  if (j < 0 || j >= rows.value.length) return
  const arr = rows.value
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
}

function cleanRows() {
  return rows.value.map(e => ({
    type: e.type, x: Number(e.x) || 0, y: Number(e.y) || 0,
    text: (e.text || '').trim() || undefined,
    field: (e.field || '').trim() || undefined,
    size: Number(e.size) || 0, bold: !!e.bold, align: e.align || 'LEFT',
    w: Number(e.w) || 0, h: Number(e.h) || 0, required: !!e.required
  }))
}

async function onSave() {
  saving.value = true
  issues.value = []
  try {
    const payload = { ...current, elements: JSON.stringify(cleanRows()) }
    if (payload.id) await labelTemplateApi.update(payload); else await labelTemplateApi.add(payload)
    ElMessage.success('已保存')
    await loadList()
  } catch (e) {
    const msg = e.message || '保存失败'
    // 服务端 gate() 把每条问题用 "; " 拼起来，这里拆回列表逐条指到元素
    issues.value = msg.split('; ')
    ElMessage.error('模板校验未通过')
  } finally { saving.value = false }
}

async function onSetDefault() {
  try {
    await labelTemplateApi.setDefault(current.id)
    ElMessage.success('已设为该类型默认模板'); await loadList()
  } catch (e) { ElMessage.error(e.message || '设置失败') }
}

async function onDelete() {
  await ElMessageBox.confirm(`删除模板 ${current.templateName}?`, '提示', { type: 'warning' })
  try {
    await labelTemplateApi.delete(current.id)
    ElMessage.success('已删除'); onNew(); await loadList()
  } catch (e) { ElMessage.error(e.message || '删除失败') }
}

onMounted(async () => {
  await loadList()
  await loadFields(current.bizType)
  // 支持从打印中心带 ?bizType=CARTON 进来，直接筛好
  const q = route.query?.bizType
  if (q && BIZ_TYPES.some(t => t.code === q)) { queryBizType.value = q; current.bizType = q; await loadList(); await loadFields(q) }
})
</script>

<style scoped>
.xk-sub { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
.geo-form { margin: 8px 0 12px; }
.canvas-hint { color: var(--el-text-color-secondary); font-size: 13px; }
.el-bar { margin: 8px 0; display: flex; gap: 6px; flex-wrap: wrap; }
.issue-list { margin: 0; padding-left: 18px; }
.foot-hint { margin-top: 10px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
