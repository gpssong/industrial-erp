<template>
  <div>
    <el-tabs v-model="activeTab" class="tab-container">
      <el-tab-pane label="系统参数" name="config"><Settings /></el-tab-pane>
      <el-tab-pane label="数据备份" name="backup"><Backup /></el-tab-pane>
      <el-tab-pane label="操作日志" name="log"><OperationLog /></el-tab-pane>
      <el-tab-pane label="打印模板" name="print">
        <div class="placeholder-card">
          <div class="ph-title">🖨️ 打印模板管理</div>
          <p>支持可视化拖拽设计各业务单据的打印模板, A4 / 小票 / 多联单 / 标签 / 条码 / 二维码均可。</p>
          <p>已支持的业务类型: 销售出库单 / 采购入库单 / 采购退货单 / 销售退货单 / 生产加工单</p>
          <el-button type="primary" size="large" @click="goPrint">
            <el-icon><Printer /></el-icon>前往打印模板
          </el-button>
        </div>
      </el-tab-pane>
      <el-tab-pane label="飞鹅打印机" name="feie"><FeiePrinterConfig /></el-tab-pane>
    </el-tabs>
  </div>
</template>
<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import Settings from './Settings.vue'
import Backup from './Backup.vue'
import OperationLog from './OperationLog.vue'
import FeiePrinterConfig from './FeiePrinterConfig.vue'
const activeTab = ref('config')
const router = useRouter()
function goPrint() { router.push('/system/print-template') }
</script>
<style scoped>
.tab-container { padding: 0 8px; }
.placeholder-card {
  max-width: 720px; margin: 32px auto;
  padding: 24px 32px;
  background: #f7f8fa; border-radius: 8px;
  text-align: center;
}
.placeholder-card p { color: #606266; font-size: 14px; line-height: 1.8; margin: 8px 0; }
.ph-title { font-size: 18px; font-weight: 600; color: #303133; margin-bottom: 12px; }
</style>
