<template>
  <div>
    <div class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="onAdd(null)">新增菜单</el-button>
      </div>
      <el-table :data="tableData" border stripe v-loading="loading" row-key="id">
        <el-table-column prop="menuName" label="菜单名称" width="160" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag :type="row.menuType==='M'?'primary':'warning'" size="small">{{ row.menuType==='M'?'菜单':'按钮' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路由" width="180" show-overflow-tooltip />
        <el-table-column prop="component" label="组件" width="200" show-overflow-tooltip />
        <el-table-column prop="perms" label="权限标识" width="160" show-overflow-tooltip />
        <el-table-column prop="icon" label="图标" width="100" />
        <el-table-column prop="sortNo" label="排序" width="60" />
        <el-table-column label="可见" width="70">
          <template #default="{ row }">
            <el-tag :type="row.isVisible===1?'success':'info'" size="small">{{ row.isVisible===1?'显示':'隐藏' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag :type="row.status===1?'success':'danger'" size="small">{{ row.status===1?'正常':'停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onAdd(row)">新增子级</el-button>
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑菜单' : '新增菜单'" :close-on-click-modal="false"
      width="1000px" top="3vh" destroy-on-close>
      <div class="dialog-body">
        <!-- 左侧表单 -->
        <div class="form-area">
          <el-form :model="form" label-width="100px">
            <el-form-item label="类型">
              <el-radio-group v-model="form.menuType">
                <el-radio value="M">菜单</el-radio>
                <el-radio value="B">按钮</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="菜单名称" required>
                  <el-input v-model="form.menuName" placeholder="如: 用户管理" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="上级菜单" v-if="menuTree.length">
                  <el-cascader v-model="form.parentId" :options="menuTree"
                    :props="{ checkStrictly: true, emitPath: false, value: 'id', label: 'menuName' }"
                    clearable style="width:100%" placeholder="最顶级" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12" v-if="form.menuType==='M'">
              <el-col :span="12">
                <el-form-item label="路由路径">
                  <el-input v-model="form.path" placeholder="如: /system/user" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="组件路径">
                  <el-input v-model="form.component" placeholder="如: system/User.vue" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="权限标识">
                  <el-input v-model="form.perms" placeholder="如: system:user:list" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="图标">
                  <el-input v-model="form.icon" placeholder="Element Plus 图标名，如: User" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="12">
              <el-col :span="8">
                <el-form-item label="排序">
                  <el-input-number v-model="form.sortNo" :min="0" style="width:100%" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="可见">
                  <el-radio-group v-model="form.isVisible">
                    <el-radio :value="1">显示</el-radio>
                    <el-radio :value="0">隐藏</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="状态">
                  <el-radio-group v-model="form.status">
                    <el-radio :value="1">正常</el-radio>
                    <el-radio :value="0">停用</el-radio>
                  </el-radio-group>
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </div>
        <!-- 右侧填写说明 -->
        <div class="code-ref">
          <div class="ref-header">📋 填写说明</div>
          <div class="ref-body">
            <div class="ref-item">
              <div class="ref-title">路由路径</div>
              <div class="ref-code">格式: /模块名/页面名</div>
              <div class="ref-example">示例: /system/user</div>
            </div>
            <div class="ref-item">
              <div class="ref-title">组件路径</div>
              <div class="ref-code">相对于 src/views 的路径</div>
              <div class="ref-example">示例: system/User.vue<br/>示例: base/Product.vue</div>
            </div>
            <div class="ref-item">
              <div class="ref-title">权限标识</div>
              <div class="ref-code">格式: 模块:页面:操作</div>
              <div class="ref-example">示例: system:user:list<br/>示例: system:user:add<br/>示例: system:user:edit</div>
            </div>
            <div class="ref-item">
              <div class="ref-title">图标</div>
              <div class="ref-code">Element Plus 图标组件名</div>
              <div class="ref-example">示例: User / Setting<br/>示例: Goods / List</div>
            </div>
            <div class="ref-item">
              <div class="ref-title">菜单 vs 按钮</div>
              <div class="ref-code">M=左侧菜单显示<br/>B=仅权限标识，不显示菜单</div>
            </div>
            <div class="ref-item">
              <div class="ref-title">示例配置</div>
              <div class="ref-example" style="font-size:11px;line-height:1.8;">
                <b>用户管理页面:</b><br/>
                路由: /system/user<br/>
                组件: system/User.vue<br/>
                权限: system:user:list<br/>
                图标: User
              </div>
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="dialogVisible=false">取消</el-button>
        <el-button type="primary" @click="onSubmit" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted, computed } from 'vue'
import { menuApi } from '@/api/system'
import { ElMessage, ElMessageBox } from 'element-plus'

const rawList = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({
  id: null, parentId: null, menuType: 'M', menuName: '', path: '', component: '',
  perms: '', icon: '', sortNo: 0, isVisible: 1, status: 1
})

const menuTree = computed(() => buildTree(rawList.value))
const tableData = computed(() => menuTree.value)

function buildTree(list) {
  const map = {}, roots = []
  list.forEach(i => { map[i.id] = { ...i, children: [] } })
  list.forEach(i => {
    if (i.parentId && map[i.parentId]) map[i.parentId].children.push(map[i.id])
    else roots.push(map[i.id])
  })
  return roots
}

async function loadData() {
  loading.value = true
  try { rawList.value = (await menuApi.list()).data } finally { loading.value = false }
}
function onAdd(parent) {
  Object.assign(form, { id: null, parentId: parent?.id || null, menuType: 'M', menuName: '', path: '', component: '', perms: '', icon: '', sortNo: 0, isVisible: 1, status: 1 })
  dialogVisible.value = true
}
function onEdit(row) {
  Object.assign(form, { id: row.id, parentId: row.parentId, menuType: row.menuType, menuName: row.menuName, path: row.path || '', component: row.component || '', perms: row.perms || '', icon: row.icon || '', sortNo: row.sortNo || 0, isVisible: row.isVisible ?? 1, status: row.status })
  dialogVisible.value = true
}
async function onSubmit() {
  if (!form.menuName) { ElMessage.warning('请填写菜单名称'); return }
  submitting.value = true
  try {
    if (form.id) await menuApi.update(form)
    else await menuApi.add(form)
    ElMessage.success('保存成功'); dialogVisible.value = false; loadData()
  } catch (e) { ElMessage.error(e.message || '保存失败') } finally { submitting.value = false }
}
async function onDelete(row) {
  await ElMessageBox.confirm(`确定删除菜单 "${row.menuName}" 吗？`, '删除确认', { type: 'warning' })
  await menuApi.delete(row.id)
  ElMessage.success('删除成功'); loadData()
}
onMounted(loadData)
</script>

<style scoped>
.toolbar { margin-bottom: 12px; }
.dialog-body { display: flex; gap: 12px; height: 65vh; }
.form-area { flex: 1; min-width: 0; overflow-y: auto; }
.code-ref {
  width: 300px; flex-shrink: 0;
  border: 1px solid #e4e7ed; border-radius: 4px;
  display: flex; flex-direction: column; overflow: hidden;
}
.ref-header {
  padding: 8px 12px; background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
  font-size: 13px; font-weight: 600; color: #303133;
}
.ref-body { overflow-y: auto; padding: 8px 12px; }
.ref-item { margin-bottom: 14px; }
.ref-title { font-size: 12px; font-weight: 600; color: #409eff; margin-bottom: 4px; }
.ref-code { font-size: 11.5px; color: #606266; font-family: monospace; background: #f5f5f5; padding: 2px 6px; border-radius: 3px; margin-bottom: 2px; }
.ref-example { font-size: 11.5px; color: #666; line-height: 1.6; }
</style>
