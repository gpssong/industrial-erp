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
      // 购销存管理 hub 页 (聚合全部原 ERP 模块入口, Figma 视觉)
      { path: 'erp', name: 'ErpHub', component: () => import('@/views/erp/Hub.vue'), meta: { title: '购销存管理', icon: 'Document' } },
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
      // v1.1.92: 领料记录 / 成品入库 — 与 sql/56 同步, 后端 controllers 已就绪
      { path: 'production/requisition', name: 'PrdRequisition', component: () => import('@/views/production/Requisition.vue'), meta: { title: '领料记录', icon: 'Notebook', perm: 'production:requisition:list' } },
      { path: 'production/finished-in', name: 'PrdFinishedIn', component: () => import('@/views/production/FinishedIn.vue'), meta: { title: '成品入库', icon: 'Box', perm: 'production:finished-in:list' } },
      // v1.1.98: 半成品入库 — 打通 原料批→半成品卷批→成品批 追溯链
      { path: 'production/semi-in', name: 'PrdSemiIn', component: () => import('@/views/production/SemiFinishedIn.vue'), meta: { title: '半成品入库', icon: 'Box', perm: 'production:semi-in:list' } },
      // v1.1.99 C4: 生产排程 + 设备 OEE
      { path: 'production/schedule', name: 'PrdSchedule', component: () => import('@/views/production/Schedule.vue'), meta: { title: '生产排程', icon: 'DataLine', perm: 'production:schedule:list' } },
      // v1.1.119: 吹膜米重采集 (ESP32 → MES 实时曲线)
      { path: 'production/weight', name: 'PrdWeight', component: () => import('@/views/production/Weight.vue'), meta: { title: '吹膜米重采集', icon: 'Odometer', perm: 'mes:weight:list' } },
      // 财务
      { path: 'finance/arap', name: 'FinArap', component: () => import('@/views/finance/Arap.vue'), meta: { title: '应收应付', icon: 'Money', perm: 'finance:arap:list' } },
      // v1.1.10+: 发票管理 (开票 / 发票列表 / 跨单合并开票)
      { path: 'finance/invoice', name: 'FinInvoice', component: () => import('@/views/finance/Invoice.vue'), meta: { title: '发票管理', icon: 'Tickets', perm: 'finance:invoice:list' } },
      { path: 'finance/invoice/create', name: 'FinInvoiceCreate', component: () => import('@/views/finance/InvoiceCreate.vue'), meta: { title: '申请开票', icon: 'Plus', perm: 'finance:invoice:add' } },
      // 报表
      { path: 'report/sales', name: 'ReportSales', component: () => import('@/views/report/Sales.vue'), meta: { title: '销售报表', icon: 'TrendCharts', perm: 'report:sales:view' } },
      // v1.1.98: 采购建议 (基于库存预警, 一键生成采购单草稿; 复用 purchase:order:add perm)
      { path: 'purchase/suggest', name: 'PurchaseSuggest', component: () => import('@/views/dashboard/PurchaseSuggest.vue'), meta: { title: '采购建议', icon: 'ShoppingCart', perm: 'purchase:order:add' } },
      { path: 'report/inventory', name: 'ReportInventory', component: () => import('@/views/report/Inventory.vue'), meta: { title: '库存报表', icon: 'PieChart', perm: 'report:inventory:view' } },
      // v1.1.68 回收站 (被软删单据分类/恢复/彻底删); v1.1.72 门禁改独立 report:recycle
      { path: 'report/recycle', name: 'ReportRecycle', component: () => import('@/views/report/Recycle.vue'), meta: { title: '回收站', icon: 'Delete', perm: 'report:recycle' } },
      // v1.1.75 AI 助手 (agent 问答 + 只读查询 + 写操作提议/确认); v1.1.76 门禁改独立 report:ai
      { path: 'report/ai', name: 'ReportAI', component: () => import('@/views/report/Ai.vue'), meta: { title: 'AI 助手', icon: 'ChatDotRound', perm: 'report:ai' } },
      // v1.1.90 QMS: 质量 KPI 报表 (质量检验页/工艺 SOP/设备安全已移除, 保留报表入口)
      { path: 'report/quality', name: 'ReportQuality', component: () => import('@/views/report/Quality.vue'), meta: { title: '质量报表', icon: 'PieChart', perm: 'quality:inspection:list' } },
      // v1.1.x PAT 补齐: 5 类检验总台账 (IQC/FAI/PAT/OQC/OQC_RETURN 合一, 含工序巡检来源/车间列)
      { path: 'report/inspection', name: 'ReportInspection', component: () => import('@/views/quality/Inspection.vue'), meta: { title: '检验台账', icon: 'List', perm: 'quality:inspection:list' } },
      // v1.1.90 EM: 设备报表
      { path: 'report/equipment', name: 'ReportEquipment', component: () => import('@/views/report/Equipment.vue'), meta: { title: '设备报表', icon: 'DataAnalysis', perm: 'equipment:maintain:list' } },
      // XK 合规中心 (XK16-204 食品接触塑料吹膜厂): 4 核心页 — 假数据先行
      { path: 'xk/material', name: 'XkMaterial', component: () => import('@/views/xk/Material.vue'), meta: { title: '原料入库', icon: 'Box', perm: 'purchase:receipt:list' } },
      { path: 'xk/trace', name: 'XkTrace', component: () => import('@/views/xk/Trace.vue'), meta: { title: '批次溯源', icon: 'Share', perm: 'production:report:list' } },
      { path: 'xk/maintenance', name: 'XkMaintenance', component: () => import('@/views/xk/Maintenance.vue'), meta: { title: '设备维保', icon: 'Tools', perm: 'work:maintain:list' } },
      { path: 'xk/safety', name: 'XkSafety', component: () => import('@/views/xk/Safety.vue'), meta: { title: '安全卫生', icon: 'CircleCheckFilled', perm: 'work:safety:list' } },
      { path: 'xk/print', name: 'XkPrint', component: () => import('@/views/xk/Print.vue'), meta: { title: '单据打印', icon: 'Printer', perm: 'system:print:list' } },
      { path: 'xk/audit', name: 'XkAudit', component: () => import('@/views/xk/Audit.vue'), meta: { title: '审计归档', icon: 'Clock', perm: 'report:view' } },
      // v1.1.101 XK — 进货查验台账
      { path: 'xk/iqc-ledger', name: 'XkIqcLedger', component: () => import('@/views/xk/IqcLedger.vue'), meta: { title: '进货查验', icon: 'Document', perm: 'qms:iqc:list' } },
      { path: 'xk/users', name: 'XkUsers', component: () => import('@/views/system/User.vue'), meta: { title: '用户权限', icon: 'User', perm: 'system:user:list' } }
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
