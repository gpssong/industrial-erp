<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="日期范围">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" style="width:240px" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="loadData">查询</el-button></el-form-item>
      </el-form>
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
  </div>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import dayjs from 'dayjs'
import { reportApi } from '@/api/report'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, BarChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
use([CanvasRenderer, LineChart, BarChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent])

const dateRange = ref([dayjs().subtract(29,'day').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])
const trend = ref([])
const ranking = ref([])
const profit = ref([])

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

async function loadData() {
  const [s, e] = dateRange.value
  const r1 = await reportApi.salesSummary({ startDate: s, endDate: e })
  trend.value = r1.data || []
  const r2 = await reportApi.salesRanking({ startDate: s, endDate: e, limit: 10 })
  ranking.value = r2.data || []
  const r3 = await reportApi.profit({ startDate: s, endDate: e })
  profit.value = r3.data || []
}
onMounted(loadData)
</script>
