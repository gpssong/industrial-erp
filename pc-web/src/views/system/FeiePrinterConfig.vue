<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="onAdd">
        <el-icon><Plus /></el-icon>新增配置
      </el-button>
      <el-button @click="loadData">
        <el-icon><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column type="index" width="50" />
      <el-table-column prop="printerName" label="配置名称" width="160" />
      <el-table-column prop="user" label="USER" width="180" show-overflow-tooltip />
      <!-- P1-4: UKey 是签名密钥, 列表仅显示后 4 位 + 鼠标悬停提示. 详情才返完整密钥 (后端 listPrinter 已 mask) -->
      <el-table-column label="UKey" width="200">
        <template #default="{ row }">
          <el-tooltip :content="row.ukey || '未设置'" placement="top" :disabled="!row.ukey">
            <span style="font-family: monospace;">{{ maskUkey(row.ukey) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="deviceSn" label="设备SN" width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" show-overflow-tooltip />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="onTest(row)">测试</el-button>
          <el-button link type="warning" @click="onEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑配置' : '新增配置'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="100px" ref="formRef" :rules="rules">
        <el-form-item label="配置名称" prop="printerName" required>
          <el-input v-model="form.printerName" placeholder="如: 车间打印机" />
        </el-form-item>
        <el-form-item label="USER" prop="user" required>
          <el-input v-model="form.user" placeholder="飞鹅云账号 (如 gpssong@163.com)" />
        </el-form-item>
        <!-- P1-4: UKey 编辑框用 password 类型, 留空表示不修改 (避免把当前密钥回传前端) -->
        <el-form-item label="UKey" prop="ukey">
          <el-input v-model="form.ukey" type="password" show-password
                    :placeholder="form.id ? '留空表示不修改' : '飞鹅 UKey (必填)'" />
        </el-form-item>
        <el-form-item label="设备SN" prop="deviceSn">
          <el-input v-model="form.deviceSn" placeholder="留空则使用首个设备" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { feiePrintApi } from '@/api/feie'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const form = ref({ id: null, printerName: '', user: '', ukey: '', deviceSn: '', remark: '' })

// P1-4: 完整 form rules, 含 USER 邮箱格式 + UKey 长度
const rules = {
  printerName: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
  user: [
    { required: true, message: '请输入飞鹅云账号', trigger: 'blur' },
    { type: 'email', message: '请输入邮箱格式', trigger: 'blur' }
  ],
  ukey: [
    // 编辑时留空 = 不修改, 不校验; 新增时必填
    {
      validator: (rule, value, cb) => {
        if (form.value.id) { cb(); return }  // 编辑模式: 留空合法
        if (!value) { cb(new Error('请输入 UKey')); return }
        if (value.length < 8) { cb(new Error('UKey 至少 8 位')); return }
        cb()
      },
      trigger: 'blur'
    }
  ]
}

// P1-4: 列表显示时把 UKey mask 成 ****xxxx 形式 (后四位可见)
function maskUkey(ukey) {
  if (!ukey) return ''
  if (ukey.length <= 4) return '****'
  return '****' + ukey.slice(-4)
}

async function loadData() {
  loading.value = true
  try {
    const r = await feiePrintApi.listPrinters()
    list.value = r.data || []
  } catch (e) {
    ElMessage.error('加载失败: ' + (e.message || ''))
  } finally {
    loading.value = false
  }
}

function onAdd() {
  form.value = { id: null, printerName: '', user: '', ukey: '', deviceSn: '', remark: '' }
  dialogVisible.value = true
}

function onEdit(row) {
  // P1-4: 编辑时不回填 ukey, 留空 = 不修改 (后端 updatePrinter 需支持 ukey 为空时跳过)
  form.value = { ...row, ukey: '' }
  dialogVisible.value = true
}

async function onSubmit() {
  // P1-7 + P2-7: 走 rules.validate()
  try { await formRef.value.validate() } catch { return }
  submitting.value = true
  try {
    if (form.value.id) {
      await feiePrintApi.updatePrinter(form.value)
      ElMessage.success('更新成功')
    } else {
      await feiePrintApi.addPrinter(form.value)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除配置 "${row.printerName}"?`, '删除确认', { type: 'warning' })
  try {
    await feiePrintApi.deletePrinter(row.id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function onTest(row) {
  try {
    const r = await feiePrintApi.testPrinter(row.user, row.ukey, row.deviceSn)
    ElMessage.success(r.data || '测试成功')
  } catch (e) {
    ElMessage.error('测试失败: ' + (e.message || '未知错误'))
  }
}

onMounted(loadData)
</script>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; gap: 8px; }
</style>
