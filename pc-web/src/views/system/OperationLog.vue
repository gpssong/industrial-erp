<template>
  <div>
    <el-tabs v-model="activeLogTab" class="log-tabs">
      <el-tab-pane label="操作日志" name="oper">
        <div class="page-card">
          <div class="search-bar">
            <el-form :model="operQuery" inline @submit.prevent>
              <el-form-item label="模块">
                <el-input v-model="operQuery.module" placeholder="如:客户管理" clearable />
              </el-form-item>
              <el-form-item label="业务">
                <el-select v-model="operQuery.businessType" placeholder="全部" clearable style="width:140px">
                  <el-option label="新增" value="ADD" />
                  <el-option label="修改" value="EDIT" />
                  <el-option label="删除" value="DELETE" />
                  <el-option label="查询" value="QUERY" />
                  <el-option label="导出" value="EXPORT" />
                  <el-option label="其它" value="OTHER" />
                </el-select>
              </el-form-item>
              <el-form-item label="操作人">
                <el-input v-model="operQuery.username" placeholder="用户账号" clearable />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="loadOperLog">查询</el-button>
                <el-button @click="resetOperQuery">重置</el-button>
                <el-button type="danger" plain @click="cleanOperLog" :loading="cleaning">清理 90 天前</el-button>
              </el-form-item>
            </el-form>
          </div>
          <el-table :data="operData.records" border stripe v-loading="operLoading">
            <el-table-column type="index" width="50" label="#" />
            <el-table-column prop="module" label="模块" width="120" />
            <el-table-column label="业务" width="80">
              <template #default="{ row }">
                <el-tag :type="bizTag(row.businessType)" size="small">{{ bizLabel(row.businessType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="username" label="操作人" width="120" />
            <el-table-column prop="ipAddress" label="IP" width="120" />
            <el-table-column prop="method" label="方法" show-overflow-tooltip min-width="160" />
            <el-table-column prop="requestUrl" label="URL" show-overflow-tooltip min-width="160" />
            <el-table-column prop="costTime" label="耗时(ms)" width="100" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
                  {{ row.status === 1 ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="operTime" label="操作时间" width="160" />
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.snapshotJson" link type="primary" size="small" @click="showSnapshot(row)">查看快照</el-button>
                <el-button v-if="row.requestParam" link type="info" size="small" @click="showParam(row)">请求参数</el-button>
                <el-button v-if="row.responseData" link type="info" size="small" @click="showResponse(row)">返回结果</el-button>
                <span v-if="!row.snapshotJson && !row.requestParam && !row.responseData" class="text-muted">—</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
            :total="Number(operData.total)" v-model:current-page="operQuery.pageNum"
            v-model:page-size="operQuery.pageSize" @current-change="loadOperLog" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="登录日志" name="login">
        <div class="page-card">
          <div class="search-bar">
            <el-form :model="loginQuery" inline @submit.prevent>
              <el-form-item label="账号">
                <el-input v-model="loginQuery.username" placeholder="登录账号" clearable />
              </el-form-item>
              <el-form-item label="结果">
                <el-select v-model="loginQuery.status" placeholder="全部" clearable style="width:110px">
                  <el-option label="成功" :value="1" />
                  <el-option label="失败" :value="0" />
                </el-select>
              </el-form-item>
              <el-form-item label="起始">
                <el-input v-model="loginQuery.start" placeholder="yyyy-MM-dd HH:mm:ss" clearable />
              </el-form-item>
              <el-form-item label="结束">
                <el-input v-model="loginQuery.end" placeholder="yyyy-MM-dd HH:mm:ss" clearable />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="loadLoginLog">查询</el-button>
                <el-button @click="resetLoginQuery">重置</el-button>
              </el-form-item>
            </el-form>
          </div>
          <el-table :data="loginData.records" border stripe v-loading="loginLoading">
            <el-table-column type="index" width="50" />
            <el-table-column prop="username" label="账号" width="140" />
            <el-table-column prop="ipAddress" label="IP" width="140" />
            <el-table-column prop="browser" label="浏览器" width="100" />
            <el-table-column prop="os" label="系统" width="100" />
            <el-table-column label="结果" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
                  {{ row.status === 1 ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="msg" label="消息" show-overflow-tooltip />
            <el-table-column prop="loginTime" label="登录时间" width="160" />
          </el-table>
          <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
            :total="Number(loginData.total)" v-model:current-page="loginQuery.pageNum"
            v-model:page-size="loginQuery.pageSize" @current-change="loadLoginLog" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 通用 JSON 弹窗 -->
    <el-dialog v-model="jsonDialog.visible" :title="jsonDialog.title" width="720px" destroy-on-close>
      <pre class="json-pre">{{ jsonDialog.text }}</pre>
      <template #footer>
        <el-button @click="copyJson">复制 JSON</el-button>
        <el-button type="primary" @click="jsonDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { operLogApi, loginLogApi } from '@/api/system'
import { ElMessage, ElMessageBox } from 'element-plus'

// === 操作日志 ===
const activeLogTab = ref('oper')
const operQuery = reactive({ pageNum: 1, pageSize: 20, module: '', businessType: '', username: '' })
const operData = ref({ records: [], total: 0 })
const operLoading = ref(false)
const cleaning = ref(false)

async function loadOperLog() {
  operLoading.value = true
  try {
    const res = await operLogApi.page({
      pageNum: operQuery.pageNum,
      pageSize: operQuery.pageSize,
      module: operQuery.module || undefined,
      businessType: operQuery.businessType || undefined,
      username: operQuery.username || undefined
    })
    operData.value = res.data || res
  } catch (e) {
    ElMessage.error('加载操作日志失败: ' + (e.message || ''))
  } finally {
    operLoading.value = false
  }
}

function resetOperQuery() {
  operQuery.module = ''
  operQuery.businessType = ''
  operQuery.username = ''
  operQuery.pageNum = 1
  loadOperLog()
}

async function cleanOperLog() {
  try {
    await ElMessageBox.confirm('确认清理 90 天前的操作日志? 此操作不可恢复', '清理确认', { type: 'warning' })
  } catch { return }
  cleaning.value = true
  try {
    await operLogApi.clean({ days: 90 })
    ElMessage.success('清理完成')
    loadOperLog()
  } catch (e) {
    ElMessage.error('清理失败: ' + (e.message || ''))
  } finally {
    cleaning.value = false
  }
}

function bizLabel(t) {
  return { ADD: '新增', EDIT: '修改', DELETE: '删除', QUERY: '查询', EXPORT: '导出', OTHER: '其它' }[t] || t || '—'
}
function bizTag(t) {
  return { ADD: 'success', EDIT: 'warning', DELETE: 'danger', QUERY: 'info', EXPORT: '', OTHER: '' }[t] || ''
}

// === 登录日志 ===
const loginQuery = reactive({ pageNum: 1, pageSize: 20, username: '', status: null, start: '', end: '' })
const loginData = ref({ records: [], total: 0 })
const loginLoading = ref(false)

async function loadLoginLog() {
  loginLoading.value = true
  try {
    const res = await loginLogApi.page({
      pageNum: loginQuery.pageNum,
      pageSize: loginQuery.pageSize,
      username: loginQuery.username || undefined,
      status: loginQuery.status == null ? undefined : loginQuery.status,
      start: loginQuery.start || undefined,
      end: loginQuery.end || undefined
    })
    loginData.value = res.data || res
  } catch (e) {
    ElMessage.error('加载登录日志失败: ' + (e.message || ''))
  } finally {
    loginLoading.value = false
  }
}

function resetLoginQuery() {
  loginQuery.username = ''
  loginQuery.status = null
  loginQuery.start = ''
  loginQuery.end = ''
  loginQuery.pageNum = 1
  loadLoginLog()
}

// === 通用 JSON 弹窗 ===
const jsonDialog = reactive({ visible: false, title: '', text: '' })

function pretty(o) {
  if (!o) return ''
  try { return JSON.stringify(typeof o === 'string' ? JSON.parse(o) : o, null, 2) }
  catch { return typeof o === 'string' ? o : JSON.stringify(o, null, 2) }
}

function showSnapshot(row) {
  jsonDialog.title = `${row.module} #${row.id} 删除前快照`
  jsonDialog.text = pretty(row.snapshotJson)
  jsonDialog.visible = true
}
function showParam(row) {
  jsonDialog.title = `请求参数 — ${row.module} ${row.businessType}`
  jsonDialog.text = pretty(row.requestParam)
  jsonDialog.visible = true
}
function showResponse(row) {
  jsonDialog.title = `返回结果 — ${row.module} ${row.businessType}`
  jsonDialog.text = pretty(row.responseData)
  jsonDialog.visible = true
}

async function copyJson() {
  try {
    await navigator.clipboard.writeText(jsonDialog.text)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('浏览器不允许直接复制, 请手动全选')
  }
}
</script>

<style scoped>
.log-tabs { padding: 0 8px; }
.search-bar { padding: 12px 0 0; }
.page-card { background: #fff; border-radius: 4px; padding: 8px 8px 4px; margin-bottom: 12px; }
.pager { display: flex; justify-content: flex-end; padding: 12px 0; }
.json-pre {
  max-height: 60vh; overflow: auto; background: #f6f8fa; border: 1px solid #e1e4e8;
  border-radius: 4px; padding: 12px; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-all;
}
.text-muted { color: #909399; font-size: 12px; }
</style>
