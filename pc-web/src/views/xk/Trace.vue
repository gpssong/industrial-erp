<template>
  <div class="xk-trace">
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">XK 质量追溯中心 / 批次溯源</div>
        <h1 class="xk-title">批次全链路追溯</h1>
      </div>
      <div class="xk-heading-actions">
        <el-radio-group v-model="direction" @change="onDirectionChange" class="direction-switch">
          <el-radio-button value="RM_TO_FG">正向 · 原料 → 成品</el-radio-button>
          <el-radio-button value="FG_TO_RM">反向 · 成品 → 原料</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <!-- XK 合规条 -->
    <div class="xk-compliance-strip">
      <el-icon class="xk-cs-icon"><CircleCheckFilled /></el-icon>
      <div class="xk-cs-body">
        <strong>XK 生产许可受控记录</strong>
        <small>支持原料→成品正向追溯与成品→原料反向追溯，原始台账长期可查 · 时间线由系统自动写入 · 服务器时间戳</small>
      </div>
      <span class="xk-cs-badge">XK 受控</span>
    </div>

    <!-- 批次台账表 -->
    <el-card class="batch-card">
      <template #header>
        <div class="card-header">
          <div><b>📦 原料批次台账</b><div class="card-sub">共 {{ batches.length }} 条批次记录</div></div>
          <el-input v-model="search" placeholder="输入批号 / 供应商 / 客户" size="default" style="width: 240px" clearable prefix-icon="Search" />
        </div>
      </template>

      <el-table :data="filteredBatches" v-loading="loading" @row-click="onRowClick" style="cursor: pointer" :row-class-name="rowClass">
        <el-table-column type="index" width="50" />
        <el-table-column prop="batchNo" label="批号" width="180">
          <template #default="{ row }">
            <span style="color: var(--primary-color); font-weight: 700">{{ row.batchNo }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="productName" label="产品" min-width="160" />
        <el-table-column prop="supplier" :label="direction === 'RM_TO_FG' ? '供应商' : '来源'" min-width="140" />
        <el-table-column prop="receiptDate" :label="direction === 'RM_TO_FG' ? '入库日期' : '出库日期'" width="120" />
        <el-table-column label="数量" width="100" align="right">
          <template #default="{ row }">{{ row.qty }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="质检状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.qcStatus === 'PASS' ? 'success' : row.qcStatus === 'HOLD' ? 'warning' : 'danger'" size="small" round>
              {{ qcLabel(row.qcStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="direction === 'RM_TO_FG' ? '对应成品批' : '对应原料批'" min-width="180">
          <template #default="{ row }">
            <span :class="{ 'text-muted': row.linked.fgBatch === '-' }">{{ row.linked.fgBatch }}</span>
            <div class="linked-sub">{{ row.linked.customer }} · {{ row.linked.qty }} {{ row.unit }}</div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 4 站时间线 (Figma 横向 timeline) -->
    <el-card v-if="timeline && timeline.stations.length" class="timeline-card">
      <template #header>
        <div class="card-header">
          <div><b>批次流转时间线 · {{ timeline.batchNo }}</b><div class="card-sub">{{ timeline.stations.length }} 站</div></div>
          <span class="xk-badge xk-badge-green">链路完整</span>
        </div>
      </template>

      <div class="xk-timeline">
        <div v-for="(s, idx) in timeline.stations" :key="s.no"
             class="xk-tl-step" :class="s.status === 'DONE' ? 'done' : 'active'">
          <i>
            <el-icon v-if="s.status === 'DONE'"><Check /></el-icon>
            <el-icon v-else><Clock /></el-icon>
          </i>
          <div class="xk-tl-text">
            <strong>{{ s.title }}</strong>
            <small>{{ s.time }} · {{ s.operator }}</small>
            <div class="xk-tl-remark" v-if="s.remark">{{ s.remark }}</div>
            <img v-if="s.photo" :src="s.photo" :alt="s.title" class="xk-tl-photo" />
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { CircleCheckFilled, Check, Clock } from '@element-plus/icons-vue'
import { xkMock } from '@/api/xk'

const direction = ref('RM_TO_FG')
const search = ref('')
const loading = ref(false)
const batches = ref([])
const timeline = ref(null)
const selectedNo = ref('')

const qcMap = { PASS: '合格', HOLD: '待定', FAIL: '不合格' }
function qcLabel(s) { return qcMap[s] || s }
function rowClass({ row }) { return row.batchNo === selectedNo.value ? 'xk-trace-row-active' : '' }

const filteredBatches = computed(() => {
  if (!search.value) return batches.value
  const q = search.value.toLowerCase()
  return batches.value.filter(b =>
    b.batchNo.toLowerCase().includes(q) ||
    (b.supplier || '').toLowerCase().includes(q) ||
    (b.linked?.customer || '').toLowerCase().includes(q)
  )
})

async function loadBatches() {
  loading.value = true
  try {
    const r = await xkMock.traceBatches(direction.value)
    if (r.code === 200) batches.value = r.data
  } finally {
    loading.value = false
  }
}

async function onRowClick(row) {
  selectedNo.value = row.batchNo
  const r = await xkMock.traceTimeline(row.batchNo)
  if (r.code === 200) {
    timeline.value = r.data
    // v1.1.99 C2 去 mock: 用全链追溯真数据回填该行的「对应成品批/客户」列
    const d = r.data || {}
    const fg = (d.downstreamBatches && d.downstreamBatches.length) ? d.downstreamBatches[0] : null
    row.linked = {
      fgBatch: fg || (d.batch?.sourcePrdOrderNo || '-'),
      customer: d.customerName || (d.customer != null ? '客户#' + d.customer : '-'),
      qty: row.qty || 0
    }
  }
}

function onDirectionChange() {
  timeline.value = null
  selectedNo.value = ''
  loadBatches()
}

onMounted(loadBatches)
</script>

<style scoped lang="scss">
.xk-trace { display: flex; flex-direction: column; gap: 16px; }

.card-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.card-sub { font-size: 12px; color: var(--muted); margin-top: 3px; }

.linked-sub { font-size: 11px; color: var(--muted); margin-top: 2px; }
.text-muted { color: #b6c0cd; }

// 批次台账表格贴近 Figma (.tr 行 hover/选中左侧蓝条)
:deep(.el-table) {
  --el-table-border-color: #edf1f4;
  --el-table-header-bg-color: #f8fafc;
  .el-table__row:hover > td { background: #f5f9fe !important; }
  .el-table__row.current-row > td { background: #f5f9fe !important; box-shadow: inset 3px 0 var(--primary-color); }
  .xk-trace-row-active > td { background: #f5f9fe !important; box-shadow: inset 3px 0 var(--primary-color); }
}

// Figma 横向 4 站时间线 (.timeline / .timeline-step)
.xk-timeline {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px;
  @media (max-width: 1050px) { grid-template-columns: 1fr 1fr; }
}
.xk-tl-step {
  position: relative; display: flex; gap: 9px; padding: 4px 14px 4px 0;
  > i {
    z-index: 1; width: 22px; height: 22px; flex: 0 0 auto;
    border: 2px solid #cbd5df; background: #fff; border-radius: 50%;
    display: grid; place-items: center; color: var(--primary-color); font-size: 12px;
  }
  &:before {
    content: ""; position: absolute; top: 11px; left: 26px; right: 8px;
    height: 2px; background: #dfe6ed;
  }
  &:last-child:before { display: none; }
  &.done > i { color: #fff; border-color: var(--success); background: var(--success); }
  &.done:before { background: var(--success); }
  &.active > i { color: #fff; border-color: var(--primary-color); background: var(--primary-color); }
}
.xk-tl-text { min-width: 0; }
.xk-tl-text strong { display: block; font-size: 12px; font-weight: 700; color: var(--text); }
.xk-tl-text small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
.xk-tl-remark { font-size: 12px; color: #526174; margin-top: 6px; line-height: 1.5; }
.xk-tl-photo { margin-top: 8px; max-width: 180px; border-radius: 8px; border: 1px solid var(--line); }

@media (max-width: 768px) {
  .xk-timeline { grid-template-columns: 1fr; }
  .xk-tl-step { padding: 0 0 18px 0; }
  .xk-tl-step:before { top: 22px; bottom: 0; left: 11px; right: auto; width: 2px; height: auto; }
}
</style>
