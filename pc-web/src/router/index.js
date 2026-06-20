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

router.beforeEach((to, from, next) => {
  const user = useUserStore()
  if (to.meta.public) return next()
  if (!user.token) return next('/login')
  // 简易权限
  if (to.meta.perm && !user.hasPerm(to.meta.perm)) {
    console.warn('[router] 权限不足:', to.meta.perm, 'user.isAdmin=', user.userInfo?.isAdmin, 'userId=', user.userInfo?.userId)
    ElMessage.warning('无访问权限: ' + to.meta.perm)
    return next(false)
  }
  next()
})

export default router
