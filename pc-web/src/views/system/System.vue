<template>
  <div>
    <el-tabs v-model="activeTab" class="tab-container">
      <el-tab-pane label="系统参数" name="config"><Settings /></el-tab-pane>
      <el-tab-pane label="打印模板" name="print"><PrintTemplate /></el-tab-pane>
      <el-tab-pane label="数据备份" name="backup"><Backup /></el-tab-pane>
    </el-tabs>
  </div>
</template>
<script setup>
import { ref } from 'vue'
import Settings from './Settings.vue'
import PrintTemplate from './PrintTemplate.vue'
import Backup from './Backup.vue'
const activeTab = ref('config')
</script>
<style scoped>
.tab-container { padding: 0 8px; }
</style>
