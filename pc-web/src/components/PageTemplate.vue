<!-- 通用 CRUD 页面模板 -->
<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="搜索..." clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
          <el-button v-if="canAdd" type="success" @click="handleAdd">新增</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <slot />
      </el-table>
      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="Number(data.total)" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize"
        :page-sizes="[10,20,50,100]" @current-change="loadData" @size-change="loadData" />
    </div>

    <!-- 通用新增弹窗 -->
    <el-dialog v-model="addVisible" title="新增" width="500px" :close-on-click-modal="false" @closed="resetForm">
      <el-form :model="addForm" label-width="100px" ref="addFormRef">
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
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="addForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAdd" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  api: { type: Object, required: true },
  canAdd: { type: Boolean, default: true }
})

const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })

function loadData() {
  loading.value = true
  props.api.page({ pageNum: Number(query.pageNum), pageSize: Number(query.pageSize), keyword: query.keyword }).then(res => {
    data.value = res.data || res
  }).finally(() => { loading.value = false })
}
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const addVisible = ref(false)
const addFormRef = ref()
const submitting = ref(false)
const addForm = reactive({ username: '', password: '123456', nickname: '', phone: '', email: '', status: 1 })

function handleAdd() {
  Object.assign(addForm, { username: '', password: '123456', nickname: '', phone: '', email: '', status: 1 })
  addVisible.value = true
}

function resetForm() {
  addFormRef.value?.resetFields()
}

async function submitAdd() {
  if (!addForm.username) {
    ElMessage.warning('请输入用户名')
    return
  }
  submitting.value = true
  try {
    await props.api.add(addForm)
    ElMessage.success('新增成功')
    addVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '新增失败')
  } finally {
    submitting.value = false
  }
}

defineExpose({ loadData })
onMounted(loadData)
</script>
<style scoped>
.pager { margin-top: 12px; text-align: right; }
</style>
