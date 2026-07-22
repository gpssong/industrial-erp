import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage } from 'element-plus'

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/dashboard/Index.vue'), meta: { title: '工作台', icon: 'Odometer' } },
      // 系统管理
      { path: 'system/user', name: 'SysUser', component: () => import('@/views/system/User.vue'), meta: { title: '用户管理', icon: 'User', perm: 'system:user:list' } },
      { path: 'system/role', name: 'SysRole', component: () => import('@/views/system/Role.vue'), meta: { title: '角色管理', icon: 'UserFilled', perm: 'system:role:list' } },
      { path: 'system/menu', name: 'SysMenu', component: () => import('@/views/system/Menu.vue'), meta: { title: '菜单管理', icon: 'Menu', perm: 'system:menu:list' } },
      { path: 'system/dept', name: 'SysDept', component: () => import('@/views/system/Dept.vue'), meta: { title: '部门管理', icon: 'OfficeBuilding', perm: 'system:dept:list' } },
      { path: 'system/settings', name: 'SysSettings', component: () => import('@/views/system/System.vue'), meta: { title: '系统设置', icon: 'Setting', perm: 'system:config:list' } },
      { path: 'system/print-template', name: 'SysPrintTemplate', component: () => import('@/views/system/PrintTemplate.vue'), meta: { title: '打印模板', icon: 'Printer', perm: 'system:print:list' } },
      { path: 'system/print-template/designer/:id', name: 'SysPrintDesigner', component: () => import('@/views/system/PrintDesigner.vue'), meta: { title: '模板设计器', perm: 'system:print:edit', hideInMenu: true } },
      // 基础资料
      { path: 'base/product', name: 'BaseProduct', component: () => import('@/views/base/Product.vue'), meta: { title: '商品管理', icon: 'Goods', perm: 'base:product:list' } },
      { path: 'base/customer', name: 'BaseCustomer', component: () => import('@/views/base/Customer.vue'), meta: { title: '客户管理', icon: 'Avatar', perm: 'base:customer:list' } },
      { path: 'base/supplier', name: 'BaseSupplier', component: () => import('@/views/base/Supplier.vue'), meta: { title: '供应商管理', icon: 'Connection', perm: 'base:supplier:list' } },
      { path: 'base/warehouse', name: 'BaseWarehouse', component: () => import('@/views/base/Warehouse.vue'), meta: { title: '仓库管理', icon: 'House', perm: 'base:warehouse:list' } },
      { path: 'base/unit', name: 'BaseUnit', component: () => import('@/views/base/Unit.vue'), meta: { title: '计量单位', icon: 'DataLine', perm: 'base:unit:list' } },
      // 采购
      { path: 'purchase/order', name: 'PurOrder', component: () => import('@/views/purchase/Order.vue'), meta: { title: '采购订单', icon: 'List', perm: 'purchase:order:list' } },
      { path: 'purchase/receipt', name: 'PurReceipt', component: () => import('@/views/purchase/Receipt.vue'), meta: { title: '采购入库', icon: 'Box', perm: 'purchase:receipt:list' } },
      { path: 'purchase/return', name: 'PurReturn', component: () => import('@/views/purchase/Return.vue'), meta: { title: '采购退货', icon: 'Back', perm: 'purchase:return:list' } },
      // 销售
      { path: 'sales/order', name: 'SalOrder', component: () => import('@/views/sales/Order.vue'), meta: { title: '销售订单', icon: 'Tickets', perm: 'sales:order:list' } },
      { path: 'sales/delivery', name: 'SalDelivery', component: () => import('@/views/sales/Delivery.vue'), meta: { title: '销售出库', icon: 'TakeawayBox', perm: 'sales:delivery:list' } },
      { path: 'sales/return', name: 'SalReturn', component: () => import('@/views/sales/Return.vue'), meta: { title: '销售退货', icon: 'Refresh', perm: 'sales:return:list' } },
      // 库存
      { path: 'inventory/stock', name: 'InvStock', component: () => import('@/views/inventory/Stock.vue'), meta: { title: '库存查询', icon: 'Grid', perm: 'inventory:stock:list' } },
      { path: 'inventory/ledger', name: 'InvLedger', component: () => import('@/views/inventory/Ledger.vue'), meta: { title: '库存台账', icon: 'Notebook', perm: 'inventory:ledger:list' } },
      { path: 'inventory/check', name: 'InvCheck', component: () => import('@/views/inventory/Check.vue'), meta: { title: '库存盘点', icon: 'Document', perm: 'inventory:check:list' } },
      // 生产
      { path: 'production/bom', name: 'PrdBom', component: () => import('@/views/production/Bom.vue'), meta: { title: 'BOM清单', icon: 'Files', perm: 'production:bom:list' } },
      { path: 'production/order', name: 'PrdOrder', component: () => import('@/views/production/Order.vue'), meta: { title: '生产加工单', icon: 'SetUp', perm: 'production:order:list' } },
      // 财务
      { path: 'finance/arap', name: 'FinArap', component: () => import('@/views/finance/Arap.vue'), meta: { title: '应收应付', icon: 'Money', perm: 'finance:arap:list' } },
      // 报表
      { path: 'report/sales', name: 'ReportSales', component: () => import('@/views/report/Sales.vue'), meta: { title: '销售报表', icon: 'TrendCharts' } },
      { path: 'report/inventory', name: 'ReportInventory', component: () => import('@/views/report/Inventory.vue'), meta: { title: '库存报表', icon: 'PieChart' } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to, from, next) => {
  const user = useUserStore()
  if (to.meta.public) return next()
  // v1.0.5 cookie 改造后 user.token 永远是空 (cookie 由浏览器自动带, JS 读不到).
  // 改用 userInfo 判断: 它在 loginAction 已持久化到 localStorage, F5 刷新会 rehydrate.
  if (!user.userInfo) return next('/login')
  // 如果 userInfo 缺失 (比如清缓存), 尝试重新拉取
  if (!user.userInfo || !user.userInfo.id) {
    try { await user.fetchMe() } catch (e) { return next('/login') }
  }
  // 简易权限
  if (to.meta.perm && !user.hasPerm(to.meta.perm)) {
    // P1-5: 不打印 user.userInfo (生产可能含敏感字段), 仅记录路径和权限名
    if (import.meta.env.DEV) console.warn('[router] 权限不足:', to.meta.perm)
    ElMessage.warning('无访问权限: ' + to.meta.perm)
    return next(false)
  }
  next()
})

export default router
