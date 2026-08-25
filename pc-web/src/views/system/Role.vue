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
    <el-dialog v-model="formVisible" :title="isEdit ? '编辑角色' : '新增角色'" width="500px" :close-on-click-modal="false" destroy-on-close>
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
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <!-- v1.0.10+: 允许登录的端 -->
        <el-form-item label="允许端" prop="clientScope">
          <el-radio-group v-model="form.clientScope">
            <el-radio value="BOTH">PC + App</el-radio>
            <el-radio value="PC">仅 PC</el-radio>
            <el-radio value="APP">仅 App</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm" :loading="submitting">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分配权限弹窗 -->
    <el-dialog v-model="permVisible" title="分配权限" width="600px" :close-on-click-modal="false" destroy-on-close>
      <el-tabs v-model="permTab">
        <el-tab-pane label="PC端菜单权限" name="pcMenu">
          <!-- v1.1.12+: check-strictly=true 关闭父子联动, 只允许勾叶子 (按钮/功能项)
               原 bug: 默认联动 + 父目录节点写入 sys_role_menu → 再次打开父目录联动子按钮全部 checked
               这里把父目录 (menuType='M') 当纯容器, 不参与勾选状态 -->
          <el-tree ref="pcMenuTreeRef" :data="allMenus" node-key="id" show-checkbox
            check-strictly
            :props="{ label: 'menuName', children: 'children', disabled: 'disabled' }" default-expand-all
            style="max-height: 400px; overflow-y: auto;">
          </el-tree>
        </el-tab-pane>
        <el-tab-pane label="App端菜单权限" name="appMenu">
          <!-- v1.1.12+: check-strictly + 父分组节点 disabled, 防止父被勾选导致子联动误勾 -->
          <el-tree ref="appMenuTreeRef" :data="appMenus" node-key="id" show-checkbox
            check-strictly
            :props="{ label: 'menuName', children: 'children', disabled: 'disabled' }" default-expand-all
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
import { ElMessage, ElMessageBox } from 'element-plus'

// v1.0.10+: App 端实际可用的菜单/功能白名单
// 维护规则: 任何 App 端新增的功能, 都要在这里登记才能在"App 端菜单权限" Tab 显示
// 与 app/src/pages/dashboard/index.vue 的 PATH_TO_APP 映射保持一致
// 通过 sys_menu.perms 字段匹配, 自动找到对应的菜单 id (sys_menu 里找不到的 perms 会被自动跳过)
// idApp 字段 (合成 ID 前缀) 用于 el-tree 唯一标识; 一个 sys_menu.perms 可能被多个 App 功能共用
// (如"生产加工单"模块下的"外勤盘点"和"新增"共用 production:order:list), 需要用不同合成 ID
//
// v1.1.12+: 严格对齐 App PATH_TO_APP — 新增商品 / 库存台账 / 外勤盘点 都保留 App 端入口.
const APP_MENU_WHITELIST = [
  { name: '商品管理', children: [
    // 新增商品: path /base/product 对应 sys_menu id=301 (perms=base:product:list), App 入口复用 /pages/base/product-add
    { name: '新增商品 (商品库)', perms: 'base:product:list', idApp: 'app-301-add' }
  ]},
  { name: '采购管理', children: [
    { name: '扫码入库', perms: 'purchase:receipt:list', idApp: 'app-402-receipt' },
    // v1.1.15+: 采购入库单查询 (sys_menu id=402 path=/purchase/receipt perms=purchase:receipt:list)
    // 注意: 与"扫码入库"复用同一 perms, 但 idApp 不同 (app-402-receipt-query)
    // buildAppMenuTree 按 perms 去重时会跳过重复项, 这里用不同 idApp 确保都能显示
    { name: '采购入库单查询', perms: 'purchase:receipt:list', idApp: 'app-402-receipt-query' }
  ]},
  { name: '销售管理', children: [
    { name: '扫码出库', perms: 'sales:return:list', idApp: 'app-503-return' },
    // v1.1.14+: 销售出库单查询 (sys_menu id=502 path=/sales/delivery perms=sales:delivery:list)
    { name: '销售出库单查询', perms: 'sales:delivery:list', idApp: 'app-502-delivery' }
  ]},
  { name: '库存管理', children: [
    { name: '查库存',   perms: 'inventory:stock:list',  idApp: 'app-601-stock' },
    { name: '库存台账', perms: 'inventory:ledger:list', idApp: 'app-602-ledger' }
  ]},
  { name: '生产管理', children: [
    // 外勤盘点: perms=inventory:check:list → sys_menu id=603 path=/inventory/check
    { name: '外勤盘点',         perms: 'inventory:check:list',  idApp: 'app-603-check' },
    // 生产加工单 (新增): perms=production:order:list → sys_menu id=702 path=/production/order
    { name: '生产加工单 (新增)', perms: 'production:order:list', idApp: 'app-702-add' }
  ]},
  { name: '报表中心', children: [
    { name: '经营简报 (KPI)', perms: 'report:view', idApp: 'app-951-report' }
  ]}
]

const query = reactive({ pageNum: 1, pageSize: 20, roleName: '' })
const data = ref({ records: [], total: 0 })
const loading = ref(false)

// 新增/编辑
const formVisible = ref(false)
const isEdit = ref(false)
const formRef = ref()
const submitting = ref(false)
const form = reactive({ id: null, roleName: '', roleCode: '', remark: '', status: 1, clientScope: 'BOTH' })

// 权限分配
const permVisible = ref(false)
const permTab = ref('pcMenu')
const permLoading = ref(false)
const currentRoleId = ref(null)
const pcMenuTreeRef = ref()
const appMenuTreeRef = ref()
const userSelectRef = ref()
const selectedUserIds = ref([])
const allMenus = ref([])
const appMenus = ref([])
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
  Object.assign(form, { id: null, roleName: '', roleCode: '', remark: '', status: 1, clientScope: 'BOTH' })
  isEdit.value = false
  formVisible.value = true
}

function handleEdit(row) {
  Object.assign(form, {
    id: row.id,
    roleName: row.roleName,
    roleCode: row.roleCode,
    remark: row.remark || '',
    status: row.status,
    clientScope: row.clientScope || 'BOTH'
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
  // P1-7: 角色删除影响该角色下所有用户的权限, 必须二次确认
  const userCount = row.userCount || 0
  const warnText = userCount > 0
    ? `确认删除角色 "${row.roleName}"?\n\n该角色下当前有 ${userCount} 个用户, 删除后这些用户的权限将被取消.`
    : `确认删除角色 "${row.roleName}"?`
  try {
    await ElMessageBox.confirm(warnText, '删除确认', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      // 用户多时要求输入角色名二次确认
      ...(userCount > 0 ? {
        inputValue: '',
        inputPlaceholder: `请输入角色名 "${row.roleName}" 以确认`,
        inputValidator: (val) => val === row.roleName || '角色名不正确',
        inputErrorMessage: '角色名不正确'
      } : {})
    })
  } catch { return }
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
  permTab.value = 'pcMenu'
  permVisible.value = true
  permLoading.value = false

  // 加载所有菜单
  if (allMenus.value.length === 0) {
    const r = await menuApi.list()
    allMenus.value = buildMenuTree(r.data || [])
  }

  // 加载该角色已分配的 PC 和 App 菜单
  // v1.0.10+: 使用新接口 /role/{id}/menus/client 分别返回 PC/APP 分类
  let pcMenusData = []
  let appMenusData = []
  try {
    const mr = await roleApi.menusByClient(row.id)
    pcMenusData = mr.data?.PC || []
    appMenusData = mr.data?.APP || []
  } catch {
    // 老版本回退: 分别调旧接口获取
    const pcR = await roleApi.menus(row.id)
    pcMenusData = pcR.data || []
    appMenusData = pcR.data || [] // 旧数据默认 BOTH → 都选中
  }
  // v1.0.10+: 按白名单构建 App 端菜单树 (只显示 App 实际可用的功能)
  appMenus.value = buildAppMenuTree(allMenus.value)
  // 设置 PC 树勾选状态
  setTimeout(() => {
    const pcKeys = pcMenusData.map(m => m.id)
    pcMenuTreeRef.value?.setCheckedKeys(pcKeys)
    // App 端: 白名单每项 _menuId 关联到 sys_menu.id, 勾选已分配的
    const appNodeToMenuId = new Map()
    for (const g of (appMenus.value || [])) {
      for (const c of (g.children || [])) appNodeToMenuId.set(c.id, c._menuId)
    }
    const assignedAppMenuIds = new Set((appMenusData || []).map(m => m.id))
    const appKeys = []
    for (const [nodeId, menuId] of appNodeToMenuId.entries()) {
      if (assignedAppMenuIds.has(menuId)) appKeys.push(nodeId)
    }
    appMenuTreeRef.value?.setCheckedKeys(appKeys)
  }, 50)

  // 加载所有用户
  if (allUsers.value.length === 0) {
    const r = await userApi.page({ pageNum: 1, pageSize: 200 })
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
  // v1.1.12+: 标记父目录 (menuType='M' 无 perms) 为 disabled — el-tree 在 check-strictly 模式下
  // 不会勾选 disabled 节点, 用户只能操作叶子 (按钮/有 perms 的功能项)
  function markDisabled(nodes) {
    for (const n of nodes) {
      // 按钮 (B) 永远可勾; 菜单节点 (M) 有 perms 的也算功能项可勾
      const grantable = n.menuType === 'B' || (n.menuType === 'M' && n.perms && n.perms.trim())
      n.disabled = !grantable
      if (n.children && n.children.length) markDisabled(n.children)
    }
  }
  markDisabled(roots)
  return roots
}

// v1.0.10+: 按 APP_MENU_WHITELIST 构造 App 端菜单树
// 节点 ID 用合成 idApp (避免同一 sys_menu.perms 被多个 App 功能共用时的 key 冲突)
// 每个节点挂 _menuId 字段, 提交时用它反查真实 sys_menu.id
function buildAppMenuTree(allMenusTree) {
  // 拍平 allMenusTree 到 byPerms 映射
  const flat = []
  function walk(nodes) {
    for (const n of (nodes || [])) {
      flat.push(n)
      if (n.children && n.children.length) walk(n.children)
    }
  }
  walk(allMenusTree)
  const byPerm = new Map()
  for (const m of flat) {
    if (!m.perms) continue
    for (const p of String(m.perms).split(',')) {
      const k = p.trim()
      if (k) byPerm.set(k, m)
    }
  }
  return APP_MENU_WHITELIST
    .map(group => {
      const children = group.children
        .map(c => {
          const m = byPerm.get(c.perms)
          if (!m) return null // 白名单里的 perms 在 sys_menu 找不到, 跳过
          // v1.1.12+: 叶子节点 enabled (disabled=false), check-strictly 下用户可单独勾选
          return { id: c.idApp, menuName: c.name, _menuId: m.id, perms: c.perms, children: [], disabled: false }
        })
        .filter(Boolean)
      // v1.1.12+: 父分组节点 disabled — 不让父节点被勾选, 避免父 checked 误带子
      return { id: `app-group-${group.name}`, menuName: group.name, children, disabled: true }
    })
    .filter(g => g.children.length > 0)
}

async function submitPerm() {
  permLoading.value = true
  try {
    if (permTab.value === 'pcMenu') {
      // v1.1.12+: check-strictly=true 下, 没有 halfKeys 概念. 只取 checkedKeys.
      // 后端 v1.1.12+ 也会过滤掉 M 父目录, 防御性双保险.
      const checkedKeys = pcMenuTreeRef.value?.getCheckedKeys() || []
      await roleApi.grantMenusByClient(currentRoleId.value, 'PC', checkedKeys)
    } else if (permTab.value === 'appMenu') {
      // v1.1.12+: App 端 check-strictly=true, 只取 checkedKeys (没有 half).
      // 树节点 ID 是合成的 idApp (避免同一 sys_menu.perms 被多 App 功能共用), 提交时翻译为真实 sys_menu.id
      const checkedKeys = appMenuTreeRef.value?.getCheckedKeys() || []
      const nodeToMenuId = new Map()
      for (const g of (appMenus.value || [])) {
        for (const c of (g.children || [])) nodeToMenuId.set(c.id, c._menuId)
      }
      const allKeys = checkedKeys
        .map(nodeId => nodeToMenuId.get(nodeId) || nodeId)
        .filter(id => Number.isFinite(Number(id)))
      await roleApi.grantMenusByClient(currentRoleId.value, 'APP', allKeys)
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
