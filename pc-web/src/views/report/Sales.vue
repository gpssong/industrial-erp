<template>
  <div>
    <div class="search-bar">
      <el-form :model="q" inline>
        <el-form-item label="日期范围">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" style="width:240px" />
        </el-form-item>
        <el-form-item label="客户">
          <el-select v-if="!customersFailed" v-model="q.customerId" filterable clearable
            placeholder="全部客户" style="width:200px">
            <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
          </el-select>
          <el-input v-else v-model="q.customer" placeholder="客户名称包含" clearable style="width:200px" />
        </el-form-item>
        <el-form-item label="商品">
          <el-input v-model="q.product" placeholder="名称/编码包含" clearable style="width:160px" />
        </el-form-item>
        <el-form-item label="汇总维度">
          <el-radio-group v-model="q.groupBy" size="small">
            <el-radio-button value="customer">按客户</el-radio-button>
            <el-radio-button value="month">按月</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadAll">查询</el-button>
        </el-form-item>
      </el-form>
      <div class="scope-tip">
        商品筛选只作用于明细表; 客户/日期同时作用于图表、明细与汇总。
      </div>
    </div>

    <el-row :gutter="12">
      <el-col :span="14">
        <el-card>
          <template #header><b>销售趋势</b></template>
          <v-chart :option="trendOpt" autoresize style="height:340px" />
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header><b>客户排行榜 TOP10</b></template>
          <el-table :data="ranking" size="small" max-height="340">
            <el-table-column type="index" width="50" />
            <el-table-column prop="customerName" label="客户" />
            <el-table-column prop="totalAmount" label="金额" align="right">
              <template #default="{ row }">¥ {{ row.totalAmount }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
    <el-card style="margin-top:12px">
      <template #header><b>毛利分析 (按月)</b></template>
      <v-chart :option="profitOpt" autoresize style="height:340px" />
    </el-card>

    <!-- ============ v1.1.145 明细 / 汇总 ============ -->
    <el-card style="margin-top:12px">
      <template #header>
        <div class="card-head">
          <b>销售明细（出库单商品行）</b>
          <div class="head-right">
            <span v-if="lines.totalRows" class="muted">
              共 {{ lines.totalRows }} 行<template v-if="lines.totals && lines.totals.billNo"> · {{ lines.totals.billNo }}</template>
            </span>
            <el-button size="small" type="primary" :loading="exporting.detail"
              :disabled="!lines.rows || !lines.rows.length" @click="exportCsv('detail')">下载明细 CSV</el-button>
          </div>
        </div>
      </template>
      <el-alert v-if="lines.error" :title="lines.error" type="warning" :closable="false" show-icon />
      <template v-else>
        <el-table :data="lines.rows" border size="small" max-height="440" v-loading="loading.lines"
          show-summary :summary-method="summaryOf(lines)">
          <el-table-column v-for="c in lines.columns" :key="c.key" :prop="c.key" :label="c.label"
            :min-width="colWidth(c.key)" :align="alignOf(c.key)" show-overflow-tooltip />
        </el-table>
        <div class="table-foot">
          <!-- v1.1.145 同日第三批: 用 size="small" 而不是旧的 small 布尔属性 ——
               element-plus 2.6 起 small 被标记弃用, 传 small 会在控制台抛
               "[el-pagination] [API] small is about to be deprecated in version 3.0.0"。 -->
          <el-pagination v-if="+lines.totalRows > 0" size="small" background layout="total, sizes, prev, pager, next, jumper"
            :total="+lines.totalRows" :page-size="q.pageSize" :current-page="q.page" :page-sizes="[20, 50, 100, 200]"
            @current-change="onPage" @size-change="onPageSize" />
          <span v-if="lines.hint" class="muted">{{ lines.hint }}</span>
        </div>
      </template>
      <div v-if="lines.note" class="note">{{ lines.note }}</div>
    </el-card>

    <el-card style="margin-top:12px">
      <template #header>
        <div class="card-head">
          <b>{{ q.groupBy === 'month' ? '销售汇总（按月 × 客户）' : '销售汇总（按客户）' }}</b>
          <div class="head-right">
            <span v-if="summary.count" class="muted">{{ summary.count }} 行</span>
            <el-tag v-if="summary.truncated" size="small" type="warning">已达行数上限, 请缩小日期范围</el-tag>
            <el-button size="small" type="primary" :loading="exporting.summary"
              :disabled="!summary.rows || !summary.rows.length" @click="exportCsv('summary')">下载汇总 CSV</el-button>
          </div>
        </div>
      </template>
      <el-alert v-if="summary.error" :title="summary.error" type="warning" :closable="false" show-icon />
      <el-table v-else :data="summary.rows" border size="small" max-height="400" v-loading="loading.summary"
        show-summary :summary-method="summaryOf(summary)">
        <el-table-column v-for="c in summary.columns" :key="c.key" :prop="c.key" :label="c.label"
          :min-width="colWidth(c.key)" :align="alignOf(c.key)" show-overflow-tooltip />
      </el-table>
      <div v-if="summary.hint && !summary.error" class="table-foot"><span class="muted">{{ summary.hint }}</span></div>
      <div v-if="summary.note" class="note">{{ summary.note }}</div>
    </el-card>
  </div>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { reportApi } from '@/api/report'
import { customerApi } from '@/api/base'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent])

const dateRange = ref([dayjs().subtract(29, 'day').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])
const trend = ref([])
const ranking = ref([])
const profit = ref([])

const q = reactive({
  customerId: null,
  customer: '',
  product: '',
  groupBy: 'customer',
  page: 1,
  pageSize: 20
})
const EMPTY = { columns: [], rows: [], totals: null, count: 0, totalRows: 0, error: '', note: '', hint: '' }
const lines = ref({ ...EMPTY })
const summary = ref({ ...EMPTY })
const loading = reactive({ lines: false, summary: false })
const exporting = reactive({ lines: false, summary: false })
const customers = ref([])
const customersFailed = ref(false)

const NUM_KEYS = ['qty', 'price', 'amount', 'taxAmount', 'amountTax', 'costAmount', 'billCount', 'totalQty', 'totalAmount']

function colWidth(k) {
  if (k === 'billNo' || k === 'batchNo') return 170
  if (k === 'customerName' || k === 'productName') return 180
  if (k === 'productCode') return 140
  if (k === 'spec') return 150
  if (k === 'billDate' || k === 'period') return 110
  if (NUM_KEYS.includes(k)) return 120
  return 110
}
// 数字列右对齐才看得出量级; 文本列左对齐。
function alignOf(k) {
  return NUM_KEYS.includes(k) ? 'right' : 'left'
}

/**
 * 合计行取后端 totals —— 它是整个查询范围的数, 不是当前页。
 * 在前端拿 rows 累加会得到一个随翻页变化的假合计, 明细表尤其危险。
 * summary-method 给的是 el-table 列对象 (只有 property, 没有我们的 key), 别按 key 取值。
 */
function summaryOf(t) {
  return ({ columns }) => columns.map((c, i) => {
    if (i === 0) return '合计'
    const v = t.totals && t.totals[c.property]
    return v === null || v === undefined ? '' : String(v)
  })
}

function rangeParams() {
  const [s, e] = dateRange.value || []
  return { startDate: s, endDate: e }
}
function filterParams(extra) {
  return { ...rangeParams(), customerId: q.customerId || '', customer: q.customer || '', ...extra }
}

const trendOpt = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['销售额', '毛利'] },
  grid: { top: 30, left: 50, right: 30, bottom: 30 },
  xAxis: { type: 'category', data: trend.value.map(t => t.date) },
  yAxis: { type: 'value' },
  series: [
    { name: '销售额', type: 'line', smooth: true, data: trend.value.map(t => +t.totalAmount || 0) },
    { name: '毛利', type: 'line', smooth: true, data: trend.value.map(t => +t.totalProfit || 0), itemStyle: { color: '#27ae60' } }
  ]
}))
const profitOpt = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['销售额', '成本', '毛利'] },
  grid: { top: 30, left: 50, right: 30, bottom: 30 },
  xAxis: { type: 'category', data: profit.value.map(t => t.month) },
  yAxis: { type: 'value' },
  series: [
    { name: '销售额', type: 'bar', data: profit.value.map(t => +t.totalAmount || 0) },
    { name: '成本', type: 'bar', data: profit.value.map(t => +t.totalCost || 0), itemStyle: { color: '#e67e22' } },
    { name: '毛利', type: 'line', smooth: true, data: profit.value.map(t => +t.totalProfit || 0), itemStyle: { color: '#c0392b' } }
  ]
}))

async function loadCharts() {
  const { startDate, endDate } = rangeParams()
  const failed = []
  const grab = async (label, call, setter) => {
    try {
      const r = await call()
      setter(r.data || [])
    } catch (e) {
      failed.push(label)
    }
  }
  // 三个图表分开兜: 一个 403 不该把另外两个一起清空, 也不该留一个未捕获的 rejection
  await Promise.all([
    grab('销售趋势', () => reportApi.salesSummary({ startDate, endDate }), v => { trend.value = v }),
    grab('客户排行', () => reportApi.salesRanking({ startDate, endDate, limit: 10 }), v => { ranking.value = v }),
    grab('毛利分析', () => reportApi.profit({ startDate, endDate }), v => { profit.value = v })
  ])
  if (failed.length) ElMessage.warning(failed.join('、') + ' 加载失败 (可能无权限)')
}

async function loadLines() {
  loading.lines = true
  try {
    const r = await reportApi.salesLines(filterParams({ product: q.product || '', page: q.page, pageSize: q.pageSize }))
    lines.value = r.data || { ...EMPTY }
  } finally {
    loading.lines = false
  }
}

async function loadSummary() {
  loading.summary = true
  try {
    const r = await reportApi.salesTable(filterParams({ groupBy: q.groupBy }))
    summary.value = r.data || { ...EMPTY }
  } finally {
    loading.summary = false
  }
}

function loadAll() {
  q.page = 1
  loadCharts()
  loadLines()
  loadSummary()
}

function onPage(p) {
  q.page = p
  loadLines()
}
function onPageSize(s) {
  q.pageSize = s
  q.page = 1
  loadLines()
}

/**
 * CSV 走后端: 屏幕上是当前页, 导出去拿屏幕数据会导出一份缺数据的报表;
 * 且 fetch 带 cookie (PC 是 httpOnly Sa-Token), axios 拦截器只认 {code:200} 的 JSON, 喂 blob 会被判成失败。
 */
async function exportCsv(scope) {
  exporting[scope] = true
  try {
    const params = scope === 'summary'
      ? filterParams({ scope, groupBy: q.groupBy })
      : filterParams({ scope, product: q.product || '' })
    const resp = await fetch(reportApi.salesExportUrl(params), { credentials: 'include' })
    const ctype = resp.headers.get('content-type') || ''
    if (!resp.ok || ctype.indexOf('json') >= 0) {
      let msg = '导出失败 (HTTP ' + resp.status + ')'
      try {
        const j = await resp.json()
        if (j && j.msg) msg = j.msg
      } catch (e) { /* 非 JSON 错误体, 用默认文案 */ }
      ElMessage.error(msg)
      return
    }
    const blob = await resp.blob()
    const cd = resp.headers.get('content-disposition') || ''
    const m = /filename\*=UTF-8''([^;]+)|filename="([^"]+)"/i.exec(cd)
    const name = m ? decodeURIComponent(m[1] || m[2]) : (scope === 'summary' ? '销售汇总.csv' : '销售明细.csv')
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = name
    a.click()
    URL.revokeObjectURL(a.href)
    ElMessage.success('已下载 ' + name)
  } catch (e) {
    ElMessage.error('导出失败: ' + (e && e.message ? e.message : e))
  } finally {
    exporting[scope] = false
  }
}

async function loadCustomers() {
  try {
    const r = await customerApi.list()
    customers.value = r.data || []
    // 没有 base:customer:list 时后端回空/报错都不该挡住销售报表: 退成手输客户名关键字
    if (!customers.value.length) customersFailed.value = true
  } catch (e) {
    customersFailed.value = true
  }
}

onMounted(() => {
  loadCustomers()
  loadAll()
})
</script>
<style scoped>
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.scope-tip {
  color: #909399;
  font-size: 12px;
  margin-top: -6px;
}
.table-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.note {
  color: #909399;
  font-size: 12px;
  margin-top: 8px;
}
</style>
