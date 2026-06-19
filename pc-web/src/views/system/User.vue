<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="搜索用户名/昵称/手机号" clearable @keyup.enter="loadData" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
          <el-button type="success" @click="handleAdd">新增</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
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
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <!-- 新增弹窗 -->
    <el-dialog v-model="addVisible" title="新增用户" width="550px" :close-on-click-modal="false" destroy-on-close>
      <el-form :model="addForm" label-width="100px" ref="addFormRef" :rules="addRules">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="addForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="addForm.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="addForm.nickname" placeholder="请输入昵称" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="addForm.phone" placeholder="请输入手机号" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="addForm.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="角色" prop="roleIds">
          <el-select v-model="addForm.roleIds" multiple placeholder="请选择角色" style="width:100%">
            <el-option v-for="r in allRoles" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="addForm.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible=false">取消</el-button>
        <el-button type="primary" @click="submitAdd" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 编辑弹窗 -->
    <el-dialog v-model="editVisible" title="编辑用户" width="550px" :close-on-click-modal="false" destroy-on-close>
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
          <el-select v-model="editForm.roleIds" multiple placeholder="请选择角色" style="width:100%">
            <el-option v-for="r in allRoles" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="editForm.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="修改密码">
          <el-checkbox v-model="editForm.changePwd">勾选后输入新密码</el-checkbox>
          <el-input v-if="editForm.changePwd" v-model="editForm.password" type="password" placeholder="请输入新密码" show-password style="margin-top:8px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible=false">取消</el-button>
        <el-button type="primary" @click="submitEdit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { userApi, roleApi } from '@/api/system'
import { ElMessage, ElMessageBox } from 'element-plus'

const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const allRoles = ref([])
const addVisible = ref(false)
const editVisible = ref(false)
const addFormRef = ref()
const editFormRef = ref()
const submitting = ref(false)

const addForm = reactive({ username: '', password: '123456', nickname: '', phone: '', email: '', roleIds: [], status: 1 })
const editForm = reactive({ id: null, username: '', nickname: '', phone: '', email: '', roleIds: [], status: 1, changePwd: false, password: '' })
const addRules = reactive({ username: [{ required: true, message: '请输入用户名', trigger: 'blur' }] })

async function loadRoles() {
  if (allRoles.value.length === 0) {
    const r = await roleApi.page({ pageNum: 1, pageSize: 999 })
    allRoles.value = (r.data?.records || [])
  }
}

async function loadData() {
  loading.value = true
  try {
    const res = await userApi.page({ pageNum: query.pageNum, pageSize: query.pageSize, keyword: query.keyword })
    data.value = res.data || res
  } finally { loading.value = false }
}

function handleAdd() {
  Object.assign(addForm, { username: '', password: '123456', nickname: '', phone: '', email: '', roleIds: [], status: 1 })
  addFormRef.value?.resetFields()
  addVisible.value = true
  loadRoles()
}

async function submitAdd() {
  if (!addForm.username) { ElMessage.warning('请输入用户名'); return }
  submitting.value = true
  try {
    const { roleIds, ...rest } = addForm
    const id = await userApi.add(rest)
    if (roleIds && roleIds.length > 0) {
      await userApi.assignRoles(id, roleIds)
    }
    ElMessage.success('新增成功')
    addVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '新增失败')
  } finally { submitting.value = false }
}

function handleEdit(row) {
  Object.assign(editForm, { id: row.id, username: row.username, nickname: row.nickname || '', phone: row.phone || '', email: row.email || '', roleIds: [], status: row.status, changePwd: false, password: '' })
  editVisible.value = true
  loadRoles()
  userApi.getRoles(row.id).then(r => { editForm.roleIds = r.data || [] }).catch(() => {})
}

async function submitEdit() {
  submitting.value = true
  try {
    const { roleIds, changePwd, password, ...rest } = editForm
    if (changePwd && password) {
      await userApi.updatePassword(editForm.id, password)
    }
    await userApi.update(rest)
    await userApi.assignRoles(editForm.id, roleIds || [])
    ElMessage.success('更新成功')
    editVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '更新失败')
  } finally { submitting.value = false }
}

async function handleDelete(row) {
  await ElMessageBox.confirm(`确定删除用户 "${row.username}" 吗？`, '删除确认', { type: 'warning' })
  try {
    await userApi.delete(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(loadData)
</script>
<style scoped>
.pager { margin-top: 12px; text-align: right; }
</style>
