<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="角色名称">
          <el-input v-model="query.roleName" placeholder="搜索..." clearable @keyup.enter="loadData" />
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
        <el-table-column prop="roleName" label="角色名称" />
        <el-table-column prop="roleCode" label="角色编码" />
        <el-table-column prop="remark" label="备注" />
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button link type="warning" size="small" @click="openPermDialog(row)">分配权限</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        :page-sizes="[10,20,50,100]" @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="formVisible" :title="isEdit ? '编辑角色' : '新增角色'" width="500px" :close-on-click-modal="false">
      <el-form :model="form" label-width="100px" ref="formRef">
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="请输入角色名称" />
        </el-form-item>
        <el-form-item label="角色编码" prop="roleCode">
          <el-input v-model="form.roleCode" placeholder="请输入角色编码" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="请输入备注" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配权限弹窗 -->
    <el-dialog v-model="permVisible" title="分配权限" width="600px" :close-on-click-modal="false">
      <el-tabs v-model="permTab">
        <el-tab-pane label="菜单权限" name="menu">
          <el-tree ref="menuTreeRef" :data="allMenus" node-key="id" show-checkbox
            :props="{ label: 'menuName', children: 'children' }" default-expand-all
            style="max-height: 400px; overflow-y: auto;">
          </el-tree>
        </el-tab-pane>
        <el-tab-pane label="关联用户" name="user">
          <el-select ref="userSelectRef" v-model="selectedUserIds" multiple placeholder="请选择关联用户" style="width: 100%">
            <el-option v-for="u in allUsers" :key="u.id" :label="u.nickname || u.username" :value="u.id" />
          </el-select>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="permVisible = false">取消</el-button>
        <el-button type="primary" @click="submitPerm" :loading="permLoading">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { roleApi, menuApi } from '@/api/system'
import { userApi } from '@/api/system'
import { ElMessage } from 'element-plus'

const query = reactive({ pageNum: 1, pageSize: 20, roleName: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)

// 新增/编辑
const formVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()
const submitting = ref(false)
const form = reactive({ id: null, roleName: '', roleCode: '', remark: '', status: 1 })

// 权限分配
const permVisible = ref(false)
const permTab = ref('menu')
const permLoading = ref(false)
const currentRoleId = ref(null)
const menuTreeRef = ref()
const userSelectRef = ref()
const selectedUserIds = ref([])
const allMenus = ref([])
const allUsers = ref([])

function loadData() {
  loading.value = true
  roleApi.page(query).then(res => {
    data.value = res.data || res
  }).catch(err => {
    console.error('加载失败:', err)
    ElMessage.error(err.message || '加载数据失败')
    data.value = { records: [], total: 0 }
  }).finally(() => { loading.value = false })
}

function handleAdd() {
  Object.assign(form, { id: null, roleName: '', roleCode: '', remark: '', status: 1 })
  isEdit.value = false
  formVisible.value = true
}

function handleEdit(row) {
  Object.assign(form, {
    id: row.id,
    roleName: row.roleName,
    roleCode: row.roleCode,
    remark: row.remark || '',
    status: row.status
  })
  isEdit.value = true
  formVisible.value = true
}

async function submitForm() {
  if (!form.roleName || !form.roleCode) {
    ElMessage.warning('请填写角色名称和编码')
    return
  }
  submitting.value = true
  try {
    if (isEdit.value) {
      await roleApi.update(form)
    } else {
      await roleApi.add(form)
    }
    ElMessage.success(isEdit.value ? '更新成功' : '新增成功')
    formVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row) {
  try {
    await roleApi.delete(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function openPermDialog(row) {
  currentRoleId.value = row.id
  permTab.value = 'menu'
  permVisible.value = true
  permLoading.value = false

  // 加载所有菜单
  if (allMenus.value.length === 0) {
    const r = await menuApi.list()
    allMenus.value = buildMenuTree(r.data || [])
  }

  // 加载该角色的已有菜单
  const mr = await roleApi.menus(row.id)
  const checkedIds = (mr.data || []).map(m => m.id)
  menuTreeRef.value?.setCheckedKeys(checkedIds)

  // 加载所有用户
  if (allUsers.value.length === 0) {
    const r = await userApi.page({ pageNum: 1, pageSize: 999 })
    allUsers.value = (r.data?.records || [])
  }

  // 加载该角色的关联用户
  const ur = await roleApi.users(row.id)
  selectedUserIds.value = ur.data || []
}

function buildMenuTree(list) {
  const map = {}
  const roots = []
  list.forEach(item => { map[item.id] = { ...item, children: [] } })
  list.forEach(item => {
    if (item.parentId === 0 || !map[item.parentId]) {
      roots.push(map[item.id])
    } else {
      map[item.parentId].children.push(map[item.id])
    }
  })
  return roots
}

async function submitPerm() {
  permLoading.value = true
  try {
    if (permTab.value === 'menu') {
      const checkedKeys = menuTreeRef.value?.getCheckedKeys() || []
      const halfKeys = menuTreeRef.value?.getHalfCheckedKeys() || []
      const allKeys = [...checkedKeys, ...halfKeys]
      await roleApi.grantMenus(currentRoleId.value, allKeys)
    } else {
      await roleApi.assignUsers(currentRoleId.value, selectedUserIds.value)
    }
    ElMessage.success('分配成功')
    permVisible.value = false
  } catch (e) {
    ElMessage.error(e.message || '分配失败')
  } finally {
    permLoading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.search-bar { padding: 16px 16px 0; }
.page-card { padding: 0 16px 16px; }
.pager { margin-top: 12px; text-align: right; }
</style>
