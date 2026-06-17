<template>
  <div>
    <el-row :gutter="12">
      <el-col :span="14">
        <el-card>
          <template #header><b>库存汇总 (按商品)</b></template>
          <v-chart :option="opt" autoresize style="height:400px" />
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header><b>呆滞库存 (30天未动)</b></template>
          <el-table :data="aging" size="small" max-height="400">
            <el-table-column prop="productName" label="商品" />
            <el-table-column prop="qty" label="数量" width="80" align="right" />
            <el-table-column prop="lastInDate" label="最后入库" width="110" />
            <el-table-column prop="days" label="天数" width="70" align="right" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue'
import { reportApi } from '@/api/report'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'
use([CanvasRenderer, PieChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent])

const summary = ref([])
const aging = ref([])
const opt = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{
    type: 'pie', radius: ['40%', '70%'],
    data: summary.value.slice(0, 10).map(s => ({ name: s.productName, value: +s.totalCost }))
  }]
}))
onMounted(async () => {
  summary.value = (await reportApi.inventorySummary()).data
  aging.value = (await reportApi.inventoryAging()).data
})
</script>
