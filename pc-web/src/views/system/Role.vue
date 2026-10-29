<template>
  <div>
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">系统管理 / 角色</div>
        <h1 class="xk-title">角色管理</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button type="primary" @click="loadData">查询</el-button>
      </div>
    </div>
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
        :total="Number(data.total)" :current-page="query.pageNum" :page-size="query.pageSize"
        :page-sizes="[10,20,50,100]" @current-change="queryOnPageChange" @size-change="queryOnSizeChange" />
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
          <!-- v1.1.56+ B: 默认只展开一级 (9 个 root 目录), 二级业务目录按需点击 checkbox 旁展开箭头展开.
               原 default-expand-all=true 在角色授权页全展开 ~95 节点, 看着极乱 -->
          <el-tree ref="pcMenuTreeRef" :data="allMenus" node-key="id" show-checkbox
            check-strictly
            :props="{ label: 'menuName', children: 'children', disabled: 'disabled' }"
            :default-expanded-keys="defaultExpanded"
            :expand-on-click-node="false"
            style="max-height: 400px; overflow-y: auto;">
          </el-tree>
        </el-tab-pane>
        <el-tab-pane label="App端菜单权限" name="appMenu">
          <!-- v1.1.12+: check-strictly + 父分组节点 disabled, 防止父被勾选导致子联动误勾 -->
          <!-- v1.1.56+ B: App 端 Tab 同 PC 端, 默认只展开一级 (商品管理/采购管理/销售管理/库存管理/生产管理/报表中心) -->
          <el-tree ref="appMenuTreeRef" :data="appMenus" node-key="id" show-checkbox
            check-strictly
            :props="{ label: 'menuName', children: 'children', disabled: 'disabled' }"
            :default-expanded-keys="appDefaultExpanded"
            :expand-on-click-node="false"
            @check="onAppCheck"
            style="max-height: 400px; overflow-y: auto;">
          </el-tree>
          <div style="color: #909399; font-size: 12px; line-height: 1.5; margin-top: 4px;">
            同一 App 权限对应多个入口时会联动勾选/取消。经营简报 / XK 单据打印、扫码报工 / XK 批次溯源、生产排程 / 设备 OEE 已拆成独立权限，可单独授权。
          </div>
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

// v1.1.130: App 端菜单权限白名单 — 按 App 实际页面维护 (不再复用 PC 菜单树).
// 对齐 app/src/pages/*、utils/permission.js 的 PAGE_PERMS、dashboard 的 APP_MENU_TO_PAGE.
// 按 sys_menu.perms 反查菜单行; 查不到的 perms 自动跳过 (需先在 sys_menu 补行).
// idApp 只作 el-tree 节点 key — 同一 perms 可对应多个 App 入口 (如生产排程/OEE).
const APP_MENU_WHITELIST = [
  { name: '生产 / 质量', children: [
    { name: '生产加工单', perms: 'production:order:list', idApp: 'app-order' },
    // v1.1.137: App 生产单详情页「飞鹅云打印」按钮 (sys_menu id=959, 同批把 status 0→1 才可授权)
    { name: '工单飞鹅打印', perms: 'production:order:feie-print', idApp: 'app-order-feie-print' },
    { name: '生产开工', perms: 'production:order:release', idApp: 'app-order-release' },
    { name: '扫码报工', perms: 'production:report:list', idApp: 'app-mes-report' },
    { name: '制袋报工', perms: 'production:report:add', idApp: 'app-mes-report-add' },
    { name: '生产排程', perms: 'production:schedule:list', idApp: 'app-schedule' },
    // v1.1.130: OEE 独立 perm (原与生产排程共用 production:schedule:list, 已拆分为可分开授权)
    { name: '设备 OEE', perms: 'production:schedule:oee', idApp: 'app-oee' },
    { name: '吹膜米重采集', perms: 'mes:weight:list', idApp: 'app-weight' },
    { name: '半成品入库', perms: 'production:semi-in:add', idApp: 'app-semi-in' },
    { name: '半成品入库审核', perms: 'production:semi-in:check', idApp: 'app-semi-in-check' },
    { name: '半成品入库反审核', perms: 'production:semi-in:uncheck', idApp: 'app-semi-in-uncheck' },
    { name: '成品入库', perms: 'production:finished-in:add', idApp: 'app-finished-in' },
    { name: '成品入库审核', perms: 'production:finished-in:check', idApp: 'app-finished-in-check' },
    { name: '成品入库反审核', perms: 'production:finished-in:uncheck', idApp: 'app-finished-in-uncheck' },
    { name: '首检 / 工序巡检', perms: 'quality:inspection:check', idApp: 'app-fai-pat' },
    { name: '质量检验', perms: 'quality:inspection:list', idApp: 'app-qms-inspection' },
    { name: '工艺 SOP', perms: 'pdm:sop:list', idApp: 'app-pdm-sop' }
  ]},
  { name: '采购管理', children: [
    { name: '原料入库 (扫码)', perms: 'purchase:receipt:list', idApp: 'app-scan-in' },
    { name: '采购入库单查询', perms: 'purchase:receipt:query', idApp: 'app-receipt-query' },
    { name: '新建入库单', perms: 'purchase:receipt:add', idApp: 'app-receipt-add' },
    { name: '采购入库审核', perms: 'purchase:receipt:check', idApp: 'app-receipt-check' },
    { name: '采购入库反审核', perms: 'purchase:receipt:uncheck', idApp: 'app-receipt-uncheck' }
  ]},
  { name: '销售管理', children: [
    { name: '扫码出库', perms: 'sales:return:list', idApp: 'app-scan-out' },
    { name: '销售出库单查询', perms: 'sales:delivery:list', idApp: 'app-delivery-list' },
    // v1.1.130: App delivery-list 页「+ 新建销售出库单」按钮由 sales:delivery:add 门禁 (前后端同一套 perm),
    //   原白名单缺该项 → 除超管外界面无法授予, App 里看不到新建入口
    { name: '销售出库新建', perms: 'sales:delivery:add', idApp: 'app-delivery-add' },
    { name: '销售出库审核', perms: 'sales:delivery:check', idApp: 'app-delivery-check' },
    { name: '销售出库反审核', perms: 'sales:delivery:uncheck', idApp: 'app-delivery-uncheck' }
  ]},
  { name: '库存管理', children: [
    { name: '查库存', perms: 'inventory:stock:list', idApp: 'app-stock' },
    { name: '库存台账', perms: 'inventory:ledger:list', idApp: 'app-ledger' },
    { name: '库存预警', perms: 'inventory:warning:list', idApp: 'app-warning' },
    { name: '外勤盘点', perms: 'inventory:check:list', idApp: 'app-count' }
  ]},
  { name: '设备 / 现场', children: [
    { name: '设备台账', perms: 'equipment:master:list', idApp: 'app-em-master' },
    { name: '设备维保', perms: 'equipment:maintain:list', idApp: 'app-em-maintain' }
  ]},
  { name: 'XK 记录', children: [
    { name: 'XK 工作台', perms: 'dashboard:kpi', idApp: 'app-xk-dashboard' },
    // v1.1.130: 批次溯源独立 perm (原与扫码报工共用 production:report:list)
    { name: 'XK 批次溯源', perms: 'production:report:trace', idApp: 'app-xk-trace' },
    { name: 'XK 设备维保', perms: 'work:maintain:list', idApp: 'app-xk-maintain' },
    { name: 'XK 安全卫生', perms: 'work:safety:list', idApp: 'app-xk-safety' },
    // v1.1.130: 单据打印独立 perm (原与经营简报共用 report:view)
    { name: 'XK 单据打印', perms: 'report:print', idApp: 'app-xk-print' },
    { name: 'XK 审计归档', perms: 'xk:audit:list', idApp: 'app-xk-audit' }
  ]},
  { name: '经营报表', children: [
    { name: '经营简报', perms: 'report:view', idApp: 'app-report-kpi' },
    { name: 'AI 助手', perms: 'report:ai', idApp: 'app-ai-assist' }
  ]},
  { name: '基础 / 系统', children: [
    { name: '新增商品', perms: 'base:product:add', idApp: 'app-product-add' },
    { name: '用户管理', perms: 'system:user:list', idApp: 'app-users' }
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
const allMenuRows = ref([])
const appMenus = ref([])
const allUsers = ref([])
// v1.1.130: 默认展开 4 个分组 + 购销存管理 (子容器按需展开), 与现侧栏一致
const defaultExpanded = ref(['10001', '10002', '10003', '10004', '10011'])
const appDefaultExpanded = APP_MENU_WHITELIST.map(g => `app-group-${g.name}`)

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
// v1.1.112 (R20): el-pagination handler 必须显式接参, 否则 pageNum 不更新, 点第 2 页无响应
function queryOnPageChange(p) { if (p === query.pageNum) return; query.pageNum = p; loadData() }
function queryOnSizeChange(s) { if (s === query.pageSize) return; query.pageSize = s; query.pageNum = 1; loadData() }

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
    // v1.1.126: 对齐 Menu.vue 整理 — 把 /workflow「设备安全」死子树从 PC 权限树里裁掉.
    // 死子树 = 1 个 M 顶层 (/workflow, status=0) + 4 个 C 子节点 (/workflow/*, status=0)
    // + 18 个 B 按钮 perm 行 (status=1, path 为空, 但 parentId 指向那 4 个 C 节点).
    // router 里已无 /workflow 路由, 真实入口是 /xk/maintenance + /xk/safety 复用
    // work:maintain:list / work:safety:list perms 字符串, 不走这些 menu 行 → 裁剪无副作用.
    // ⚠️ 不用雪花 id 做锚点 (R21): 19 位雪花 id 字面量经 vite minify 会超出 Number.MAX_SAFE_INTEGER
    // 被抹平成同一个 hex 值, 23 个不同 id 全塌成 1 个 → 过滤一行都裁不掉. 改用稳定字符串 path 前缀.
    // 判定: 节点自身 path 以 /workflow 开头, 或沿 parentId 上溯任意祖先 path 以 /workflow 开头
    // (覆盖 18 个 path 为空的 B 按钮). 裁掉整棵子树, 不影响其它节点.
    const all = r.data || []
    // v1.1.130: 根级 B/F 是历史模块遗留的孤立权限点 (委外/工艺参数/校准等),
    // 不进 PC 导航树 (否则弹窗底部会冒出一排无归属的勾选项); App 树仍从全量行反查 perm.
    const live = all.filter(m => !inWorkflowDeadSubtree(m, all))
    allMenuRows.value = live
    allMenus.value = pruneDisabled(buildMenuTree(
      live.filter(m => !(Number(m.parentId) === 0 && (m.menuType === 'B' || m.menuType === 'F')))
    ))
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
  // v1.1.130: App 树从全量菜单行构建 (含根级 F 权限行), 不复用 PC 导航树
  appMenus.value = buildAppMenuTree(allMenuRows.value.length ? allMenuRows.value : allMenus.value)
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

// v1.1.130: 停用的菜单 (status=0) 连同子树一起从 PC 权限树移除 —
// 授权到 status=0 的 menu_id 不会生效, 显示出来只会误导.
// 必须在 buildMenuTree 之后剪枝: 先过滤行会把子节点变成孤儿顶到根上.
function pruneDisabled(nodes) {
  const out = []
  for (const n of (nodes || [])) {
    if (Number(n.status) !== 1) continue
    n.children = pruneDisabled(n.children)
    out.push(n)
  }
  return out
}

// v1.1.126: /workflow「设备安全」死子树裁剪判定 (PC 权限树).
// 锚点用稳定字符串 path (以 /workflow 开头), 不用雪花 id (R21: 字面量 minify 后塌成一个值).
// 规则: 节点自身 path 是 /workflow*, 或沿 parentId 上溯任意祖先 path 是 /workflow* → 属于死子树.
// 覆盖 18 个 path 为空但 parentId 指向 /workflow/* 的 B 按钮 perm 行.
function inWorkflowDeadSubtree(node, fullList) {
  const isDeadPath = (p) => !!p && p.startsWith('/workflow')
  if (isDeadPath(node.path)) return true
  const byId = {}
  for (const m of fullList) byId[m.id] = m   // m.id 来自后端 JSON 是字符串, 作对象 key 无精度问题
  let cur = byId[node.parentId]
  let guard = 0
  while (cur && guard++ < 50) {
    if (isDeadPath(cur.path)) return true
    cur = byId[cur.parentId]
  }
  return false
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
  // v1.1.53+: 对齐后端 isGrantableMenu (v1.1.52.6 已认 F 类型), 把 F 类型 perm 行也判为可授权.
  // 否则 dashboard 子模块 (sales-kpi/sales-trend/sales-ranking/inventory-warning) 这 4 行
  // F 类型 perm 节点会全显示为 disabled, 用户没法勾选 (虽然后端 isGrantableMenu 已经认).
  // v1.1.80+: 补 C+perms 可授权 (飞鹅「飞鹅打印机查询」「飞鹅打印日志」等 C 型真实功能点
  //   也可勾). 与后端 isGrantableMenu (SysRoleService.java) 保持一致, 否则 PC 弹窗里
  //   这些节点是 disabled 灰色, 用户无法勾选 — 即使后端已经放开.
  function markDisabled(nodes) {
    for (const n of nodes) {
      // 按钮 (B) 永远可勾; 页面菜单 (C) / 功能项 (F) / 菜单节点 (M) 带 perms 也算功能项可勾
      const grantable = n.menuType === 'B'
        || ((n.menuType === 'C' || n.menuType === 'F' || n.menuType === 'M') && n.perms && n.perms.trim())
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
    // v1.1.130: 只认启用行 — 同一 perms 在库里可能有已停用的历史行 (/workflow),
    // 命中它会导致授权写入 status=0 的 menu_id, App 取权限时被过滤掉。
    if (!m.perms || Number(m.status) !== 1) continue
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

// v1.1.130: 共用同一 sys_menu 授权行的白名单项, 勾选状态联动 —
// 同一 perms 可能对应多个 App 入口 (经营简报 ↔ XK 单据打印 同为 report:view;
// 扫码报工 ↔ XK 批次溯源 同为 production:report:list; 生产排程 ↔ 设备 OEE 同为 production:schedule:list),
// 它们解析到同一个 _menuId, 授权按 menu_id 落库。不联动时取消其中一个、另一个仍勾着,
// 提交后授权行保留, 重进弹窗又显示已勾 (报障: 取消经营简报后重进仍是勾选)。
function onAppCheck(nodeObj) {
  if (!nodeObj || nodeObj._menuId == null) return
  const tree = appMenuTreeRef.value
  if (!tree) return
  const checked = (tree.getCheckedKeys() || []).includes(nodeObj.id)
  for (const g of (appMenus.value || [])) {
    for (const c of (g.children || [])) {
      if (c.id !== nodeObj.id && String(c._menuId) === String(nodeObj._menuId)) {
        tree.setChecked(c.id, checked)
      }
    }
  }
}

async function submitPerm() {  permLoading.value = true
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
