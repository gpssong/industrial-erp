<template>
  <div>
    <!-- 顶部 4 KPI 卡片 -->
    <div class="kpi-row">
      <el-card shadow="never">
        <template #header><span class="muted-tip">本月检验单总数</span></template>
        <div class="kpi-value">{{ kpi.total }}</div>
        <div class="muted-tip">{{ rangeLabel }}</div>
      </el-card>
      <el-card shadow="never">
        <template #header><span class="muted-tip">通过率</span></template>
        <div class="kpi-value" style="color:#67c23a">{{ kpi.passRate }}%</div>
        <div class="muted-tip">{{ kpi.passed }} / {{ kpi.total }}</div>
      </el-card>
      <el-card shadow="never">
        <template #header><span class="muted-tip">不良率</span></template>
        <div class="kpi-value" style="color:#f56c6c">{{ kpi.defectRate }}%</div>
        <div class="muted-tip">不良 {{ kpi.unqualified }} / 抽检 {{ kpi.sampled }}</div>
      </el-card>
      <el-card shadow="never">
        <template #header><span class="muted-tip">拒收率</span></template>
        <div class="kpi-value" style="color:#e6a23c">{{ kpi.rejectRate }}%</div>
        <div class="muted-tip">拒收 {{ kpi.rejected }} / {{ kpi.total }}</div>
      </el-card>
    </div>

    <div class="page-card" style="margin-top:16px">
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div class="section-title">📊 质量 KPI 明细 (数据范围: {{ rangeLabel }})</div>
        <el-radio-group v-model="rangeMode" @change="load">
          <el-radio-button value="month">本月</el-radio-button>
          <el-radio-button value="30d">近 30 天</el-radio-button>
        </el-radio-group>
      </div>

      <el-tabs v-model="activeTab">
        <!-- 缺陷分布 -->
        <el-tab-pane label="缺陷分布" name="defect">
          <div class="muted-tip" style="margin-bottom:8px">按 缺陷类型 + 严重度 聚合 (前端本地 group by)</div>
          <el-table :data="defectDist" border stripe v-loading="loading" size="small">
            <el-table-column prop="type" label="缺陷类型" min-width="180">
              <template #default="{ row }">{{ row.type || '(未分类)' }}</template>
            </el-table-column>
            <el-table-column label="严重度" width="120">
              <template #default="{ row }">{{ { MINOR: '轻微', MAJOR: '一般', CRITICAL: '严重' }[row.severity] || row.severity || '-' }}</template>
            </el-table-column>
            <el-table-column prop="count" label="次数" width="120" align="right" />
          </el-table>
          <el-empty v-if="!defectDist.length && !loading" description="该范围内无缺陷记录" />
        </el-tab-pane>

        <!-- 检验结果分布 -->
        <el-tab-pane label="检验结果分布" name="result">
          <div class="muted-tip" style="margin-bottom:12px">按结果计数 (PASS / FAIL / HOLD / FAIL_REJECT)</div>
          <div v-for="it in resultDist" :key="it.key" style="margin-bottom:12px">
            <div style="display:flex;justify-content:space-between;margin-bottom:4px">
              <span>{{ resultLabel(it.key) }} <span class="muted-tip">({{ it.count }} 单)</span></span>
              <span class="muted-tip">{{ it.pct }}%</span>
            </div>
            <el-progress :percentage="it.pct" :color="resultColor(it.key)" :stroke-width="16"
              :format="() => ''" />
          </div>
          <el-empty v-if="!resultDist.length && !loading" description="该范围内无检验结果" />
        </el-tab-pane>

        <!-- 按产品不良率 -->
        <el-tab-pane label="按产品不良率" name="product">
          <div class="muted-tip" style="margin-bottom:8px">按 产品 聚合 不良率 = 不良 / 抽检</div>
          <el-table :data="productDefect" border stripe v-loading="loading" size="small" default-sort-by="rate" :default-sort="{ prop: 'rate', order: 'descending' }">
            <el-table-column prop="product" label="产品" min-width="180">
              <template #default="{ row }">{{ row.product || '(未知)' }}</template>
            </el-table-column>
            <el-table-column prop="sampled" label="抽检量" width="120" align="right" />
            <el-table-column prop="unqualified" label="不良量" width="120" align="right">
              <template #default="{ row }"><span style="color:#f56c6c">{{ row.unqualified }}</span></template>
            </el-table-column>
            <el-table-column label="不良率" width="140">
              <template #default="{ row }">
                <el-progress :percentage="row.rate" :color="row.rate > 5 ? '#f56c6c' : (row.rate > 0 ? '#e6a23c' : '#67c23a')" :stroke-width="12" />
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!productDefect.length && !loading" description="该范围内无产品级数据" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { inspectionApi } from '@/api/quality'

const loading = ref(false)
const rangeMode = ref('month')
const activeTab = ref('defect')
// 拉全量 + 前端 group by (数据量小时简单; 数据量大时留 TODO: 走 qualityReportApi 服务端聚合)
const inspections = ref([])
const defects = ref([])

const kpi = reactive({ total: 0, passed: 0, rejected: 0, sampled: 0, unqualified: 0, passRate: 0, defectRate: 0, rejectRate: 0 })
const defectDist = ref([])
const resultDist = ref([])
const productDefect = ref([])

function rangeStart() {
  const d = new Date()
  if (rangeMode.value === 'month') {
    const first = new Date(d.getFullYear(), d.getMonth(), 1)
    return first.toISOString().slice(0, 10)
  }
  const past = new Date(d.getTime() - 29 * 86400000)
  return past.toISOString().slice(0, 10)
}
const rangeLabel = computed(() => {
  if (rangeMode.value === 'month') {
    const d = new Date()
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + ' (本月)'
  }
  return '近 30 天'
})

function num(v) { return Number(v) || 0 }

function resultLabel(k) {
  return { PASS: '合格', FAIL: '不合格', HOLD: '冻结', FAIL_REJECT: '拒收', PASS_DISPOSITION: '合格(处置)' }[k] || k
}
function resultColor(k) {
  return { PASS: '#67c23a', PASS_DISPOSITION: '#95d47b', FAIL: '#f56c6c', HOLD: '#e6a23c', FAIL_REJECT: '#c45656' }[k] || '#409eff'
}

async function load() {
  loading.value = true
  try {
    const start = rangeStart()
    // 拉全量当页 (大 pageSize, 前端按日期过滤 + group by)
    const r = await inspectionApi.page({ pageNum: 1, pageSize: 500 })
    let rows = r?.data?.records || []
    // 按范围过滤 (billDate 字符串比较)
    rows = rows.filter(x => {
      const bd = String(x.billDate || '').slice(0, 10)
      return bd >= start
    })
    inspections.value = rows

    // 聚合 KPI
    const total = rows.length
    const passed = rows.filter(x => ['PASS', 'PASS_DISPOSITION'].includes(x.result)).length
    const rejected = rows.filter(x => x.result === 'FAIL_REJECT').length
    const sampled = rows.reduce((s, x) => s + num(x.sampleQty), 0)
    const unqualified = rows.reduce((s, x) => s + num(x.unqualifiedQty), 0)
    Object.assign(kpi, {
      total, passed, rejected, sampled, unqualified,
      passRate: total ? Math.round((passed / total) * 100) : 0,
      defectRate: sampled ? Math.round((unqualified / sampled) * 1000) / 10 : 0,
      rejectRate: total ? Math.round((rejected / total) * 100) : 0
    })

    // 缺陷分布 (type + severity 聚合): 需要 detail 拉 defects. 数据量大时留 TODO, 简化: 只对当前范围样本缺陷聚合.
    // 这里先拉前 N 条 detail 的 defects (避免全量 N+1), 简化版仅用已 loaded 的 defects.
    defectDist.value = aggregateDefects()

    // 结果分布
    const byResult = {}
    for (const x of rows) {
      const k = x.result || 'HOLD'
      byResult[k] = (byResult[k] || 0) + 1
    }
    resultDist.value = Object.keys(byResult)
      .map(k => ({ key: k, count: byResult[k], pct: total ? Math.round((byResult[k] / total) * 100) : 0 }))
      .sort((a, b) => b.count - a.count)

    // 按产品不良率
    const byProduct = {}
    for (const x of rows) {
      const p = x.productName || '(未知)'
      if (!byProduct[p]) byProduct[p] = { product: p, sampled: 0, unqualified: 0, rate: 0 }
      byProduct[p].sampled += num(x.sampleQty)
      byProduct[p].unqualified += num(x.unqualifiedQty)
    }
    productDefect.value = Object.values(byProduct)
      .map(p => ({ ...p, rate: p.sampled ? Math.round((p.unqualified / p.sampled) * 1000) / 10 : 0 }))
      .sort((a, b) => b.rate - a.rate)
  } catch (e) {
    console.warn('[Quality] 报表加载失败:', e)
    ElMessage.error((e && (e.msg || e.message)) || '加载失败')
  } finally {
    loading.value = false
  }
}

// 简化版: 缺陷分布从列表页内联的 defects 聚合 (若后端列表不返回 defects, 则留 TODO 走 detail N 条)
function aggregateDefects() {
  const map = new Map()
  for (const ins of inspections.value) {
    const defs = ins.defects || []
    for (const d of defs) {
      const key = (d.defectType || '(未分类)') + '|' + (d.severity || '-')
      const cur = map.get(key) || { type: d.defectType || '(未分类)', severity: d.severity || '-', count: 0 }
      cur.count += num(d.qty) || 1
      map.set(key, cur)
    }
  }
  return Array.from(map.values()).sort((a, b) => b.count - a.count)
}

onMounted(load)
</script>

<style scoped>
.section-title { font-size:15px;font-weight:600;color:#333 }
.muted-tip { color:#909399;font-size:12px }
.kpi-row { display:grid;grid-template-columns:repeat(4,1fr);gap:12px }
.kpi-value { font-size:32px;font-weight:700;color:#303133 }
</style>
