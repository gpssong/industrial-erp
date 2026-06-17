<template>
  <PageTemplate :api="api" :can-add="true" ref="tmpl">
    <el-table-column type="index" width="60" label="序号" />
    <el-table-column prop="username" label="用户名" />
    <el-table-column prop="nickname" label="昵称" />
    <el-table-column prop="phone" label="手机号" />
    <el-table-column prop="email" label="邮箱" />
    <el-table-column prop="status" label="状态" width="80">
      <template #default="{ row }">
        <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="createTime" label="创建时间" width="160" />
    <el-table-column label="操作" width="150" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
        <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
      </template>
    </el-table-column>
  </PageTemplate>

  <!-- 编辑弹窗 -->
  <el-dialog v-model="editVisible" title="编辑用户" width="550px" :close-on-click-modal="false">
    <el-form :model="editForm" label-width="100px" ref="editFormRef">
      <el-form-item label="用户名">
        <el-input v-model="editForm.username" disabled />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="editForm.nickname" placeholder="请输入昵称" />
      </el-form-item>
      <el-form-item label="手机号" prop="phone">
        <el-input v-model="editForm.phone" placeholder="请输入手机号" />
      </el-form-item>
      <el-form-item label="邮箱" prop="email">
        <el-input v-model="editForm.email" placeholder="请输入邮箱" />
      </el-form-item>
      <el-form-item label="角色" prop="roleIds">
        <el-select v-model="editForm.roleIds" multiple placeholder="请选择角色" style="width: 100%">
          <el-option v-for="r in allRoles" :key="r.id" :label="r.roleName" :value="r.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-radio-group v-model="editForm.status">
          <el-radio :label="1">启用</el-radio>
          <el-radio :label="0">停用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editVisible = false">取消</el-button>
      <el-button type="primary" @click="submitEdit" :loading="submitting">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import PageTemplate from '@/components/PageTemplate.vue'
import { userApi, roleApi } from '@/api/system'
import { ElMessage } from 'element-plus'

const api = userApi
const tmpl = ref()
const editVisible = ref(false)
const editFormRef = ref()
const submitting = ref(false)
const allRoles = ref([])
const editForm = reactive({ id: null, username: '', nickname: '', phone: '', email: '', roleIds: [], status: 1 })

function handleEdit(row) {
  Object.assign(editForm, {
    id: row.id,
    username: row.username,
    nickname: row.nickname || '',
    phone: row.phone || '',
    email: row.email || '',
    roleIds: [],
    status: row.status
  })
  editVisible.value = true
  // 加载角色列表和用户已有角色
  loadRolesAndUserRoles()
}

async function loadRolesAndUserRoles() {
  if (allRoles.value.length === 0) {
    const r = await roleApi.page({ pageNum: 1, pageSize: 999 })
    allRoles.value = (r.records || [])
  }
  if (editForm.id) {
    const r = await userApi.getRoles(editForm.id)
    editForm.roleIds = r.data || []
  }
}

async function submitEdit() {
  submitting.value = true
  try {
    await api.update(editForm)
    await api.assignRoles(editForm.id, editForm.roleIds)
    ElMessage.success('更新成功')
    editVisible.value = false
    tmpl.value.loadData()
  } catch (e) {
    ElMessage.error(e.message || '更新失败')
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  try {
    await api.delete(row.id)
    ElMessage.success('删除成功')
    tmpl.value.loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}
</script>
