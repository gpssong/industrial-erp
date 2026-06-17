<template>
  <div class="page-card">
    <h3 class="industrial-title" style="margin-bottom:12px">菜单管理</h3>
    <p style="color:#666">通过「角色管理 - 权限分配」控制菜单可见性。完整菜单编辑功能可基于本框架扩展。</p>
    <el-tree :data="tree" :props="{ label: 'menuName', children: 'children' }" default-expand-all />
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { menuApi } from '@/api/system'
const tree = ref([])
onMounted(async () => { tree.value = (await menuApi.list()).data })
</script>
