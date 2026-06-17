<template>
  <div class="page-card">
    <h3 class="industrial-title" style="margin-bottom:12px">部门管理</h3>
    <el-table :data="list" border>
      <el-table-column prop="deptName" label="部门名称" />
      <el-table-column prop="leader" label="负责人" />
      <el-table-column prop="phone" label="电话" />
      <el-table-column prop="status" label="状态">
        <template #default="{ row }"><el-tag :type="row.status===1?'success':'info'">{{ row.status===1?'正常':'停用' }}</el-tag></template>
      </el-table-column>
    </el-table>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { deptApi } from '@/api/system'
const list = ref([])
onMounted(async () => { list.value = (await deptApi.list()).data })
</script>
