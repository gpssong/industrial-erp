<template>
  <div class="dashboard">
    <!-- v1.1.53: 4 个 section 各自独立可见性, 不再"无 report:view 就整页静默" -->
    <el-row :gutter="12" v-if="kpiVisible">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <el-card class="kpi-card" :body-style="{ padding: '16px' }">
          <div class="kpi-inner" :style="{ borderLeft: `4px solid ${k.color}` }">
            <div class="kpi-label">{{ k.label }}</div>
            <div class="kpi-value">¥ {{ k.value }}</div>
            <div class="kpi-sub">{{ k.sub }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" style="margin-top:12px" v-if="trendVisible">
      <el-col :span="16">
        <el-card>
          <template #header><b>销售趋势 (近30天)</b></template>
          <v-chart :option="chartOption" autoresize style="height:320px" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header><b>快捷功能</b></template>
          <div class="quick-grid">
            <el-button v-for="q in quickLinks" :key="q.path" type="primary" plain @click="$router.push(q.path)">
              <el-icon><component :is="q.icon" /></el-icon>
              {{ q.title }}
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" style="margin-top:12px" v-if="warningVisible || rankingVisible">
      <el-col :span="12" v-if="warningVisible">
        <el-card>
          <template #header><b>库存预警</b></template>
          <el-table :data="warningList" size="small" max-height="240">
            <el-table-column prop="productCode" label="编码" width="90" />
            <el-table-column prop="productName" label="商品" />
            <el-table-column prop="warehouseName" label="仓库" width="80" />
            <el-table-column prop="qty" label="当前库存" width="80" align="right">
              <template #default="{ row }">
                <span style="color:#c0392b;font-weight:bold">{{ row.qty }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="safetyStock" label="安全库存" width="80" align="right" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12" v-if="rankingVisible">
        <el-card>
          <template #header><b>销售排行榜 TOP10</b></template>
          <el-table :data="ranking" size="small" max-height="240">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="customerName" label="客户" />
            <el-table-column prop="totalAmount" label="金额" width="120" align="right">
              <template #default="{ row }">¥ {{ row.totalAmount }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { reportApi } from '@/api/report'
import { stockApi } from '@/api/inventory'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent])

const userStore = useUserStore()

// v1.1.53: 4 个 section 各自独立 perm 门禁
//  - kpiVisible     → dashboard:kpi            (新 perm, 之前归 report:view)
//  - trendVisible   → dashboard:sales-trend    (新 perm)
//  - rankingVisible → dashboard:sales-ranking  (新 perm)
//  - warningVisible → inventory:warning:list   (复用已有 perm, sql/28)
const kpiVisible     = computed(() => userStore.hasPerm('dashboard:kpi'))
const trendVisible   = computed(() => userStore.hasPerm('dashboard:sales-trend'))
const rankingVisible = computed(() => userStore.hasPerm('dashboard:sales-ranking'))
const warningVisible = computed(() => userStore.hasPerm('inventory:warning:list'))

const kpis = ref([
  { label: '今日销售', value: '0.00', sub: '元', color: '#1e6091' },
  { label: '销售总额', value: '0.00', sub: '元', color: '#27ae60' },
  { label: '今日采购', value: '0.00', sub: '元', color: '#e67e22' },
  { label: '应收余额', value: '0.00', sub: '元', color: '#c0392b' }
])
const warningList = ref([])
const ranking = ref([])
const trend = ref([])

const quickLinks = [
  { path: '/sales/delivery', title: '销售出库', icon: 'TakeawayBox' },
  { path: '/purchase/receipt', title: '采购入库', icon: 'Box' },
  { path: '/production/order', title: '生产加工单', icon: 'SetUp' },
  { path: '/inventory/stock', title: '库存查询', icon: 'Grid' },
  { path: '/finance/arap', title: '应收应付', icon: 'Money' },
  { path: '/report/sales', title: '销售报表', icon: 'TrendCharts' }
]

const chartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['销售金额', '销售毛利'] },
  grid: { top: 30, left: 50, right: 30, bottom: 30 },
  xAxis: { type: 'category', data: trend.value.map(t => t.date) },
  yAxis: { type: 'value' },
  series: [
    { name: '销售金额', type: 'line', smooth: true, data: trend.value.map(t => t.amount), itemStyle: { color: '#1e6091' } },
    { name: '销售毛利', type: 'line', smooth: true, data: trend.value.map(t => t.profit), itemStyle: { color: '#27ae60' } }
  ]
}))

onMounted(async () => {
  // v1.1.53: 各 section 独立加载 + 独立 try/catch 静默 — 无 perm 时不调接口 (避免 403 干扰)
  // v1.1.11 老逻辑 (整页门禁 report:view) 已废弃: 现在 4 个 section 各自判定

  if (kpiVisible.value) {
    try {
      const r = await reportApi.dashboard()
      const d = r.data || {}
      kpis.value[0].value = (d.todaySales || 0).toFixed(2)
      kpis.value[1].value = (d.totalSales || 0).toFixed(2)
      kpis.value[2].value = (d.todayPurchase || 0).toFixed(2)
      kpis.value[3].value = (d.arBalance || 0).toFixed(2)
    } catch (e) { /* 静默 — request.js 也会处理 401/403 不弹 toast */ }
  }

  if (trendVisible.value) {
    try {
      const end = dayjs().format('YYYY-MM-DD')
      const start = dayjs().subtract(29, 'day').format('YYYY-MM-DD')
      const rs = await reportApi.salesSummary({ startDate: start, endDate: end })
      trend.value = rs.data || []
    } catch (e) { /* 静默 */ }
  }

  if (rankingVisible.value) {
    try {
      const end = dayjs().format('YYYY-MM-DD')
      const start = dayjs().subtract(29, 'day').format('YYYY-MM-DD')
      const rk = await reportApi.salesRanking({ startDate: start, endDate: end, limit: 10 })
      ranking.value = rk.data || []
    } catch (e) { /* 静默 */ }
  }

  // v1.1.44: 加载库存预警 (qty < safety_stock) — 已有的 inventory:warning:list perm
  if (warningVisible.value) {
    try {
      const w = await stockApi.warningList()
      const raw = w.data || []
      warningList.value = raw
        .map(item => ({
          productName: item.product_name || item.productName || '-',
          productCode: item.product_code || item.productCode || '',
          qty: item.qty != null ? Number(item.qty) : 0,
          safetyStock: (item.safety_stock != null ? item.safety_stock : item.p_safety_stock) != null ? Number(item.safety_stock != null ? item.safety_stock : item.p_safety_stock) : 0,
          warehouseName: item.wh_name || item.warehouseName || ''
        }))
      warningList.value = warningList.value.filter(item => item.qty < item.safetyStock)
    } catch (e) { /* 静默 */ }
  }
})
</script>

<style scoped>
.kpi-card .kpi-inner { padding-left: 12px; }
.kpi-label { font-size: 12px; color: #888; }
.kpi-value { font-size: 24px; font-weight: bold; margin: 4px 0; color: #333; }
.kpi-sub { font-size: 12px; color: #999; }
.quick-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
</style>
