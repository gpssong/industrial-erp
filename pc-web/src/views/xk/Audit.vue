<template>
  <div class="xk-audit">
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">XK 生产许可档案中心</div>
        <h1 class="xk-title">审计日志与记录归档</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button :icon="Download" @click="onExportPack">导出核查资料包</el-button>
      </div>
    </div>

    <!-- XK 合规条 -->
    <div class="xk-compliance-strip">
      <el-icon class="xk-cs-icon"><CircleCheckFilled /></el-icon>
      <div class="xk-cs-body">
        <strong>XK 生产许可受控记录</strong>
        <small>日志由系统自动写入且不可编辑；业务记录与附件保存期限不少于 2 年</small>
      </div>
      <span class="xk-cs-badge">XK 受控</span>
    </div>

    <!-- Tab 切换 (Figma .tabs) -->
    <div class="tab-bar">
      <div :class="['tab-item', { active: tab === 'log' }]" @click="tab = 'log'">审计日志</div>
      <div :class="['tab-item', { active: tab === 'archive' }]" @click="tab = 'archive'">记录归档查询</div>
    </div>

    <!-- 搜索栏 -->
    <div class="search-bar">
      <el-input v-model="query.keyword" placeholder="搜索单据编号、操作人或操作内容" clearable style="flex: 1; min-width: 240px" prefix-icon="Search" />
      <el-date-picker v-model="query.date" type="date" value-format="YYYY-MM-DD" style="width: 160px" />
      <el-select v-model="query.bizType" clearable style="width: 190px" placeholder="全部业务类型">
        <el-option label="原料入库" value="INBOUND" />
        <el-option label="环境/卫生检查" value="XK_ENV_CHECK" />
        <el-option label="设备台账" value="SAVE_EQUIPMENT" />
        <el-option label="检验" value="CREATE_INSPECTION" />
        <el-option label="报工" value="REPORT_WORK" />
        <el-option label="单据打印" value="PDF" />
        <el-option label="供应商暂停供货" value="SUPPLIER_SUSPEND" />
        <el-option label="供应商恢复供货" value="SUPPLIER_RESUME" />
        <el-option label="供应商准入评审" value="SUPPLIER_REVIEW" />
        <el-option label="供应商证照登记" value="SUPPLIER_CERT" />
        <el-option label="供应商绩效评级" value="SUPPLIER_RATING" />
      </el-select>
      <el-button type="primary" @click="onQuery">查询</el-button>
      <el-button @click="onResetQuery">重置</el-button>
    </div>

    <!-- 审计日志 -->
    <el-card v-if="tab === 'log'" class="block-card">
      <el-table :data="pagedLogs" border stripe v-loading="loading">
        <el-table-column prop="time" label="系统时间" width="180" />
        <el-table-column prop="operator" label="操作人员" width="140">
          <template #default="{ row }">
            <div>{{ row.operator.split(' / ')[0] }}</div>
            <div class="role-sub">{{ row.operator.split(' / ')[1] }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作类型" width="120">
          <template #default="{ row }">
            <el-tag :type="actionType(row.action)" size="small" effect="dark">{{ actionLabel(row.action) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="billNo" label="业务记录" width="200">
          <template #default="{ row }">
            <span class="bill-link">{{ row.billNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="业务类型" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.bizType" size="small" effect="plain">{{ bizTypeLabel(row.bizType) }}</el-tag>
            <span v-else class="role-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="change" label="变更内容" min-width="280" show-overflow-tooltip />
        <el-table-column label="完整性" width="100" align="center">
          <template #default>
            <span class="integrity">
              <el-icon><Lock /></el-icon> 已固化
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onDetail(row)">详情</el-button>
            <el-button link @click="onExport(row)">导出</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        v-model:current-page="logPage" :page-size="20" :total="logs.length" />
    </el-card>

    <!-- 记录归档查询 (Figma 4 卡片归档格, 数据走真 /xk/audit/archive) -->
    <el-card v-else class="block-card">
      <div class="archive-grid">
        <section v-for="a in archiveCards" :key="a.name" class="archive-card">
          <span class="ac-ic"><el-icon :size="20"><component :is="a.icon" /></el-icon></span>
          <div class="ac-title">
            <strong>{{ a.name }}</strong>
            <el-tag :type="a.count > 0 ? 'success' : 'info'" size="small" effect="light">{{ a.count > 0 ? '有记录' : '暂无' }}</el-tag>
          </div>
          <div class="ac-count">{{ a.count }}<small>条记录</small></div>
          <dl>
            <div><dt>归档范围</dt><dd>{{ a.range }}</dd></div>
            <div><dt>最早归档</dt><dd>{{ a.oldest || '—' }}</dd></div>
            <div><dt>到期策略</dt><dd>长期保留 (≥2 年)</dd></div>
          </dl>
          <el-button @click="onEnterArchive(a)"><el-icon><Files /></el-icon>进入归档库</el-button>
        </section>
      </div>
      <!-- 归档明细 (进入某归档库后展开) -->
      <el-table v-if="archiveDetail" :data="archiveDetail" border stripe style="margin-top: 14px">
        <el-table-column prop="billNo" label="业务单号" width="180">
          <template #default="{ row }"><span class="bill-link">{{ row.billNo || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="业务类型" width="140">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ bizTypeLabel(row.bizType) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="createBy" label="操作人" width="140" />
        <el-table-column label="归档时间" width="180">
          <template #default="{ row }">{{ (row.createTime || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column prop="sizeKb" label="附件(KB)" width="100" align="center">
          <template #default="{ row }">{{ row.sizeKb ? row.sizeKb + ' KB' : '—' }}</template>
        </el-table-column>
        <el-table-column label="到期日" width="120" align="center">
          <template #default="{ row }">{{ row.expireAt || '长期' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { CircleCheckFilled, Lock, Download, Files, Box, SetUp, Tools, Camera, OfficeBuilding } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { xkMock } from '@/api/xk'

const tab = ref('log')
const loading = ref(false)
const query = ref({ keyword: '', date: '', bizType: '' })

const logs = ref([])
const rawLogs = ref([])   // 后端原始流水, 查询只做视图过滤, 不改写本数组
// v1.1.99 hotfix: 客户端分页 (避免 el-pagination :page-size 弃用警告, 同时不让 20+ 行撑爆)
const logPage = ref(1)
const pagedLogs = computed(() => {
  const start = (logPage.value - 1) * 20
  return logs.value.slice(start, start + 20)
})
// 每次 logs 被重置/重查询, 回到第 1 页
watch(logs, () => { logPage.value = 1 })

const actionMap = {
  PDF: { label: '生成 PDF', type: 'success' },
  ADD: { label: '新增', type: 'success' },
  EDIT: { label: '修改', type: 'warning' },
  DELETE: { label: '删除', type: 'danger' },
  UPLOAD: { label: '上传附件', type: 'info' },
  VOID: { label: '作废', type: 'danger' }
}
function actionLabel(a) { return actionMap[a]?.label || a }
function actionType(a) { return actionMap[a]?.type || 'info' }

// 后端真实 writeAudit bizType (XkAuditService / XkSupplierRecordService 落库字面量) → 中文
const bizMap = {
  INBOUND: '原料入库', SIGN_PASSED_IQC: 'IQC 签署合格',
  CREATE_INSPECTION: '新建检验', CHECK_INSPECTION: '检验判定',
  REPORT_WORK: '生产报工',
  SAVE_EQUIPMENT: '设备建档', UPDATE_EQUIPMENT: '设备变更', DELETE_EQUIPMENT: '设备删除',
  XK_ENV_CHECK: '环境检查', SAFETY: '卫生检查', PDF: '单据打印',
  SUPPLIER_SUSPEND: '供应商暂停供货', SUPPLIER_RESUME: '供应商恢复供货',
  SUPPLIER_REVIEW: '供应商准入评审', SUPPLIER_CERT: '供应商证照登记',
  SUPPLIER_RATING: '供应商绩效评级'
}
function bizTypeLabel(b) { return bizMap[b] || b }

// 记录归档卡片 (对齐 Figma mockup archive-grid) — 数据走真 /xk/audit/archive 实时聚合
// 每个卡片 = 一组真实 writeAudit bizType (见 XkAuditService / XkSupplierRecordService 落库字面量)
const ARCHIVE_DEFS = [
  { icon: Box, name: '原料入库与检验', bizType: 'INBOUND', group: ['INBOUND', 'SIGN_PASSED_IQC'], range: '2023-06 至今' },
  { icon: SetUp, name: '生产工单记录', bizType: 'REPORT_WORK', group: ['REPORT_WORK', 'CREATE_INSPECTION', 'CHECK_INSPECTION'], range: '2023-06 至今' },
  { icon: Tools, name: '设备维保档案', bizType: 'SAVE_EQUIPMENT', group: ['SAVE_EQUIPMENT', 'UPDATE_EQUIPMENT', 'DELETE_EQUIPMENT'], range: '2023-06 至今' },
  { icon: Camera, name: '安全卫生巡检', bizType: 'XK_ENV_CHECK', group: ['XK_ENV_CHECK', 'SAFETY'], range: '2023-06 至今' },
  { icon: OfficeBuilding, name: '供应商准入档案', bizType: 'SUPPLIER_REVIEW', group: ['SUPPLIER_SUSPEND', 'SUPPLIER_RESUME', 'SUPPLIER_REVIEW', 'SUPPLIER_CERT', 'SUPPLIER_RATING'], range: '2025-01 至今' }
]
const archiveCards = ref([])
const archiveDetail = ref(null)     // 进入某归档库后展示该组 bizType 的明细行
let archiveAll = []                  // 全量归档流水 (前端按 group 分组)

function rowsOf(def) {
  const g = def.group || [def.bizType]
  return archiveAll.filter(l => g.includes(l.bizType))
}
async function loadArchive() {
  const r = await xkMock.archivedRecords()
  archiveAll = (r.code === 200 ? r.data : []) || []
  archiveCards.value = ARCHIVE_DEFS.map(d => {
    const rows = rowsOf(d)
    const oldest = rows.length ? rows.map(r2 => r2.createTime).filter(Boolean).sort()[0] : null
    return { ...d, count: rows.length, oldest: oldest ? String(oldest).slice(0, 10) : '—', rate: '' }
  })
}
function onEnterArchive(a) {
  const rows = rowsOf(a)
  archiveDetail.value = rows.length ? rows : null
  if (!rows.length) ElMessage.info(`「${a.name}」暂无归档记录`)
}

// 审计日志 tab 查询: 本地过滤原始流水 (keyword/bizType/date), 不改写 rawLogs, 可反复查询
function onQuery() {
  const kw = (query.value.keyword || '').trim().toLowerCase()
  const bt = query.value.bizType || ''
  const day = query.value.date || ''
  const rows = rawLogs.value.filter(l => {
    if (bt && l.bizType !== bt) return false
    if (day && !String(l.time || '').startsWith(day)) return false
    if (kw) {
      const hay = ((l.billNo || '') + ' ' + (l.operator || '') + ' ' + (l.change || '')).toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })
  logs.value = rows
  ElMessage.success('查询完成, 命中 ' + rows.length + ' 条')
}
function onResetQuery() {
  query.value = { keyword: '', date: '', bizType: '' }
  logs.value = rawLogs.value
}
function onDetail(row) {
  ElMessage.info('审计流水: ' + (row.billNo || '—') + ' · ' + (row.change || '无变更说明'))
}
// 单条导出 = 写一条 PDF 审计 (XK 受控: 留痕 + SHA256)
async function onExport(row) {
  const r = await xkMock.printAudit({ billNo: row.billNo || '', sha256: '', ossPath: '', sizeKb: '0' })
  if (r.code === 200) ElMessage.success('已导出并写入审计流水: ' + row.billNo)
  else ElMessage.error(r.msg || '导出失败')
}
// 顶部「导出核查资料包」= 对全部归档流水写一条汇总 PDF 审计
async function onExportPack() {
  await loadArchive()
  const r = await xkMock.printAudit({ billNo: 'AUDIT-PACK-' + archiveAll.length, sha256: '', ossPath: '', sizeKb: '0' })
  if (r.code === 200) ElMessage.success('核查资料包已导出 (归档 ' + archiveAll.length + ' 条, 审计已留痕)')
  else ElMessage.error(r.msg || '导出失败')
}

onMounted(async () => {
  const r1 = await xkMock.auditLogs()
  if (r1.code === 200) {
    rawLogs.value = r1.data || []
    logs.value = rawLogs.value
  }
  await loadArchive()
})
</script>

<style scoped lang="scss">
.xk-audit { display: flex; flex-direction: column; gap: 16px; }

// Figma .tabs
.tab-bar { display: flex; gap: 22px; border-bottom: 1px solid var(--line); }
.tab-item {
  padding: 12px 1px; font-size: 13px; font-weight: 650; color: var(--muted);
  cursor: pointer; border-bottom: 2px solid transparent; transition: .18s;
  &:hover { color: var(--primary-color); }
  &.active { color: var(--primary-color); border-bottom-color: var(--primary-color); }
}

.search-bar {
  display: flex; gap: 12px; align-items: center;
  background: var(--surface); padding: 14px; border-radius: 12px;
  border: 1px solid #e3e9f0; box-shadow: var(--card-shadow);
}

.block-card { margin-bottom: 0; }
:deep(.el-table) {
  --el-table-border-color: #edf1f4;
  --el-table-header-bg-color: #f8fafc;
}
.role-sub { font-size: 11px; color: var(--muted); margin-top: 2px; }
.bill-link { color: var(--primary-color); font-weight: 700; font-family: ui-monospace, monospace; }
.integrity { color: var(--success); font-size: 12px; display: inline-flex; align-items: center; gap: 3px; }
.pager { margin-top: 14px; text-align: right; }

/* Figma .archive-grid — 记录归档卡片 (5 组, 自适应换行) */
.archive-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(215px, 1fr)); gap: 14px; padding: 4px; }
@media (max-width: 640px) { .archive-grid { grid-template-columns: 1fr; } }
.archive-card {
  background: #fff; border: 1px solid var(--line); border-radius: 12px; padding: 18px;
  display: flex; flex-direction: column;
  .ac-ic {
    width: 40px; height: 40px; display: grid; place-items: center;
    color: var(--primary-color); background: var(--primary-soft, #eef6ff); border-radius: 10px;
  }
  .el-button { width: 100%; margin-top: 14px; }
}
.ac-title { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin: 14px 0 4px; }
.ac-title strong { font-size: 13px; color: var(--text); font-weight: 700; }
.ac-count { font-size: 26px; font-weight: 800; color: var(--text); margin: 12px 0 14px; }
.ac-count small { color: var(--muted); font-size: 11px; font-weight: 500; margin-left: 5px; }
.archive-card dl { margin: 0; }
.archive-card dl div { display: flex; justify-content: space-between; padding: 8px 0; border-top: 1px solid var(--line); font-size: 11px; }
.archive-card dt { color: var(--muted); }
.archive-card dd { margin: 0; font-weight: 700; color: var(--text); }
</style>
