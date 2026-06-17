<template>
  <div>
    <div class="search-bar">
      <el-form :model="query" inline>
        <el-form-item label="关键字"><el-input v-model="query.keyword" clearable placeholder="编码/名称/电话" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadData">查询</el-button>
          <el-button type="success" @click="onAdd">新增客户</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card">
      <el-table :data="data.records" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="customerCode" label="编码" width="120" />
        <el-table-column prop="customerName" label="客户名称" />
        <el-table-column prop="customerType" label="类型" width="100" />
        <el-table-column prop="priceLevel" label="价格等级" width="100" />
        <el-table-column prop="contactPerson" label="联系人" width="100" />
        <el-table-column prop="phone" label="电话" width="120" />
        <el-table-column prop="creditLimit" label="授信额度" width="120" align="right" />
        <el-table-column prop="creditUsed" label="已用" width="100" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status===1?'success':'info'">{{ row.status===1?'启用':'停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="data.total" v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" @current-change="loadData" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑客户' : '新增客户'" width="700px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="编码"><el-input v-model="form.customerCode" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="名称"><el-input v-model="form.customerName" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contactPerson" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="价格等级">
            <el-select v-model="form.priceLevel" style="width:100%">
              <el-option label="零售价" value="RETAIL" />
              <el-option label="批发价" value="WHOLESALE" />
              <el-option label="大客户价" value="VIP" />
              <el-option label="经销商价" value="DISTRIBUTOR" />
            </el-select>
          </el-form-item></el-col>
          <el-col :span="12"><el-form-item label="税率(%)"><el-input-number v-model="form.taxRate" :precision="2" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="授信额度"><el-input-number v-model="form.creditLimit" :precision="2" :min="0" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="税号"><el-input v-model="form.taxNo" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { customerApi } from '@/api/base'
import { ElMessage, ElMessageBox } from 'element-plus'

const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)
const dialogVisible = ref(false)
const form = reactive({
  id: null, customerCode: '', customerName: '', customerType: 'NORMAL',
  priceLevel: 'RETAIL', contactPerson: '', phone: '', address: '',
  taxRate: 13.00, creditLimit: 0, creditUsed: 0, status: 1, remark: ''
})

async function loadData() { loading.value = true; try { data.value = (await customerApi.page(query)).data } finally { loading.value = false } }
function onAdd() { Object.assign(form, { id: null, customerCode: '', customerName: '', creditLimit: 0 }); dialogVisible.value = true }
function onEdit(row) { Object.assign(form, row); dialogVisible.value = true }
async function onDelete(row) {
  await ElMessageBox.confirm(`删除 ${row.customerName}?`, '提示', { type: 'warning' })
  await customerApi.delete(row.id); ElMessage.success('已删除'); loadData()
}
async function onSave() {
  if (form.id) await customerApi.update(form)
  else await customerApi.add(form)
  ElMessage.success('已保存'); dialogVisible.value = false; loadData()
}
onMounted(loadData)
</script>
<style scoped>.pager { margin-top: 12px; text-align: right; }</style>
