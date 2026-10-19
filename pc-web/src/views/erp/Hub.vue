<template>
  <div class="erp-hub">
    <!-- 面包屑 + 大标题 + 右侧动作 (对齐 Figma 购销存页) -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">供应链 / 经营业务</div>
        <h1 class="xk-title">购销存与经营管理</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="onLog"><el-icon><Clock /></el-icon>业务日志</el-button>
        <el-button type="primary" @click="onNew"><el-icon><Plus /></el-icon>新建单据</el-button>
      </div>
    </div>

    <div class="erp-hub-grid">
      <!-- 左列: 分类卡已抽出到全局 MainLayout/HubSidebar.vue 常驻组件 (v1.1.92),
           此处只放右栏内容; layout 由 MainLayout .content-shell.with-hub 提供 -->
      <div class="erp-hub-right">
        <div class="quick-card">
          <div class="quick-card-head">
            <div>
              <b>{{ activeCatObj.title }}管理</b>
              <small>选择业务功能快速进入</small>
            </div>
            <el-tag size="small" effect="plain">{{ activeCatObj.links.length }} 项功能</el-tag>
          </div>
          <div class="quick-grid" :class="quickColsClass">
            <div
              v-for="l in activeCatObj.links"
              :key="l.path"
              class="quick-item"
              @click="goLink(l)"
            >
              <el-icon class="qi-icon"><component :is="l.icon || 'ArrowRight'" /></el-icon>
              <span class="qi-title">{{ l.title }}</span>
              <el-icon class="qi-arrow"><ArrowRight /></el-icon>
            </div>
          </div>
        </div>

        <div class="records-card">
          <div class="records-card-head">
            <div>
              <b>{{ activeCatObj.title }}管理最新记录</b>
              <small>与现有 ERP 单据字段保持一致的业务入口</small>
            </div>
            <div class="records-actions">
              <el-input v-model="recQuery" placeholder="搜索编号或名称" clearable style="width:200px">
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>
              <el-button plain @click="onExport"><el-icon><Download /></el-icon>导出</el-button>
            </div>
          </div>

          <el-table :data="filteredRecords" size="small" empty-text="暂无记录">
            <el-table-column prop="no" label="单据 / 编号" width="180" />
            <el-table-column prop="party" label="往来单位 / 名称" min-width="160" />
            <el-table-column prop="content" label="业务内容" min-width="160" />
            <el-table-column label="数量 / 金额" width="150" align="right">
              <template #default="{ row }">{{ row.amount ? '¥ ' + row.amount : '—' }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="110" align="center">
              <template #default="{ row }">
                <span class="rec-pill" :class="pillClass(row.status)">{{ row.status }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="ai-card">
          <div class="ai-left">
            <el-icon :size="22" color="var(--primary-color)"><ChatDotRound /></el-icon>
            <div>
              <b>经营 AI 助手</b>
              <small>可以询问"本月食品袋销售额""哪些原料库存不足"或"待收款客户"</small>
            </div>
          </div>
          <el-button text type="primary" @click="router.push('/report/ai')">开始提问
            <el-icon><ArrowRight /></el-icon>
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight, Clock, Plus, Search, Download, ChatDotRound,
  Goods, Box, TakeawayBox, Files, Money, Grid, Menu, TrendCharts,
  Document, RefreshLeft, User, OfficeBuilding, Odometer, Coin, CreditCard
} from '@element-plus/icons-vue'
import { productApi, customerApi, supplierApi } from '@/api/base'
import { salOrderApi, salDeliveryApi, salReturnApi } from '@/api/sales'
import { arapApi } from '@/api/finance'

const router = useRouter()
const route = useRoute()

// 功能分类 (对应 Figma 购销存页左侧卡片) — 每个分类聚合现有 ERP 模块
// v1.1.92+: 采购管理 / 应收应付 数据对齐 mockup erpRows (purchase / finance)
const categories = [
  {
    key: 'base', title: '基础资料', icon: 'Goods',
    sub: '商品管理 · 客户管理 · 供应商管理 · 仓库管理 · 计量单位',
    links: [
      { path: '/base/product', title: '商品管理', icon: 'Goods' },
      { path: '/base/customer', title: '客户管理', icon: 'User' },
      { path: '/base/supplier', title: '供应商管理', icon: 'OfficeBuilding' },
      { path: '/xk/material', title: '合格供应商', icon: 'CircleCheck' },
      { path: '/base/warehouse', title: '仓库管理', icon: 'Box' },
      { path: '/base/unit', title: '计量单位', icon: 'Odometer' }
    ]
  },
  {
    key: 'purchase', title: '采购管理', icon: 'Box',
    sub: '采购计划 · 采购订单 · 原料检验 · 采购入库 · 采购退货',
    links: [
      { path: '/purchase/order', title: '采购计划', icon: 'Download' },
      { path: '/purchase/order', title: '采购订单', icon: 'Document' },
      { path: '/base/supplier', title: '供应商', icon: 'OfficeBuilding' },
      { path: '/purchase/receipt', title: '采购入库', icon: 'Download' },
      { path: '/purchase/return', title: '采购退货', icon: 'RefreshLeft' }
    ]
  },
  {
    key: 'sales', title: '销售管理', icon: 'TakeawayBox',
    sub: '销售订单 · 销售出库 · 销售退货',
    links: [
      { path: '/sales/order', title: '销售订单' },
      { path: '/sales/delivery', title: '销售出库' },
      { path: '/sales/return', title: '销售退货' }
    ]
  },
  {
    key: 'inventory', title: '库存管理', icon: 'Grid',
    sub: '库存查询 · 库存台账 · 库存盘点 · 批次追溯',
    links: [
      { path: '/inventory/stock', title: '库存查询' },
      { path: '/inventory/ledger', title: '库存台账' },
      { path: '/inventory/check', title: '库存盘点' },
      { path: '/xk/trace', title: '批次追溯' }
    ]
  },
  {
    key: 'production', title: '生产管理', icon: 'Files',
    sub: 'BOM 清单 · 生产加工单 · 领料记录 · 成品入库',
    links: [
      { path: '/production/bom', title: 'BOM 清单' },
      { path: '/production/order', title: '生产加工单' },
      { path: '/production/requisition', title: '领料记录' },
      { path: '/production/finished-in', title: '成品入库' }
    ]
  },
  {
    key: 'arap', title: '应收应付', icon: 'Money',
    sub: '客户应收 · 供应商应付 · 收款记录 · 付款记录',
    links: [
      { path: '/finance/arap', title: '客户应收', icon: 'Coin' },
      { path: '/finance/arap', title: '供应商应付', icon: 'Money' },
      { path: '/finance/arap', title: '收款记录', icon: 'CreditCard' },
      { path: '/finance/arap', title: '付款记录', icon: 'CreditCard' }
    ]
  },
  {
    key: 'report', title: '报表中心', icon: 'TrendCharts',
    sub: '销售报表 · 库存报表 · 采购报表 · 经营分析',
    links: [
      { path: '/report/sales', title: '销售报表' },
      { path: '/report/inventory', title: '库存报表' },
      { path: '/report/ai', title: 'AI 助手' },
      { path: '/report/recycle', title: '回收站' }
    ]
  },
  {
    key: 'system', title: '系统管理', icon: 'Menu',
    sub: '用户管理 · 角色管理 · 菜单管理 · 部门管理 · 系统设置',
    links: [
      { path: '/system/user', title: '用户管理' },
      { path: '/system/role', title: '角色管理' },
      { path: '/system/menu', title: '菜单管理' },
      { path: '/system/dept', title: '部门管理' },
      { path: '/system/settings', title: '系统设置' },
      { path: '/system/print-template', title: '打印模板' }
    ]
  }
]

const activeCat = ref('production')
// v1.1.92: 跟随 ?cat=xxx query 参数 (HubSidebar 点击分类时跳转 /erp?cat=base 等)
watch(() => route.query.cat, (q) => {
  if (q && typeof q === 'string' && categories.some(c => c.key === q)) activeCat.value = q
}, { immediate: true })
const activeCatObj = computed(() => categories.find(c => c.key === activeCat.value) || categories[0])
// 快捷入口列数 (>=5 全宽 5 列; 4 项 4 列; 其余 3 列)
const quickColsClass = computed(() => {
  const n = activeCatObj.value.links.length
  if (n >= 5) return 'cols-5'
  if (n === 4) return 'cols-4'
  return ''
})

// 右列标题跟随左列选中分类 (对齐 Figma)
const catTitle = computed(() => activeCatObj.value.title)

// 最新记录 (按左列分类切换, 与现有 ERP 单据字段一致) — 数据走真后端 page() API, 仅取最新 6 条
// 分类 → 单据类型映射: 一个分类可聚合多个真实单据 API, 各自取 page(pageNum=1,pageSize=6) 后合并按创建时间倒序
// 状态码映射 (真实 billStatus DRAFT/CHECKED + invoiceStatus) → 中文标签, 复用 pillClass 中文配色
const STATUS_LABEL = {
  DRAFT: '草稿', CHECKED: '已审核', VOIDED: '已作废',
  UNINVOICED: '未开票', PARTIAL_INVOICED: '部分开票', FULL_INVOICED: '已开票'
}
// 每个分类的取数器: async (page: (params)=>Promise) => 归一化成 {no,party,content,amount,status} 行
const LOADERS = {
  base: async () => {
    const [p, c, s] = await Promise.all([
      productApi.page({ pageNum: 1, pageSize: 2 }).catch(() => ({ code: 500, data: {} })),
      customerApi.page({ pageNum: 1, pageSize: 2 }).catch(() => ({ code: 500, data: {} })),
      supplierApi.page({ pageNum: 1, pageSize: 2 }).catch(() => ({ code: 500, data: {} }))
    ])
    const rows = []
    ;(p.data?.records || []).forEach(r => rows.push({ no: r.productCode || ('SP-' + r.id), party: r.productName, content: r.spec || '商品', amount: '', status: r.status === 1 ? '启用' : '停用' }))
    ;(c.data?.records || []).forEach(r => rows.push({ no: r.customerNo || ('KH-' + r.id), party: r.customerName, content: '客户', amount: '', status: r.status === 1 ? '正常' : '停用' }))
    ;(s.data?.records || []).forEach(r => rows.push({ no: r.supplierNo || ('GYS-' + r.id), party: r.supplierName, content: '供应商', amount: '', status: r.status === 1 ? '正常' : '停用' }))
    return rows
  },
  purchase: async () => {
    const [o, r] = await Promise.all([
      (await import('@/api/purchase')).purOrderApi.page({ pageNum: 1, pageSize: 6 }).catch(() => ({ code: 500, data: {} })),
      (await import('@/api/purchase')).purReceiptApi.page({ pageNum: 1, pageSize: 6 }).catch(() => ({ code: 500, data: {} }))
    ])
    const rows = []
    ;(o.data?.records || []).forEach(x => rows.push({ no: x.billNo, party: x.supplierName || '—', content: '采购订单', amount: x.totalAmount != null ? '¥ ' + x.totalAmount : '', status: STATUS_LABEL[x.billStatus] || x.billStatus }))
    ;(r.data?.records || []).forEach(x => rows.push({ no: x.billNo, party: x.supplierName || '—', content: '采购入库', amount: x.totalAmount != null ? '¥ ' + x.totalAmount : '', status: STATUS_LABEL[x.billStatus] || x.billStatus }))
    return rows
  },
  sales: async () => {
    const [o, d, ret] = await Promise.all([
      salOrderApi.page({ pageNum: 1, pageSize: 4 }).catch(() => ({ code: 500, data: {} })),
      salDeliveryApi.page({ pageNum: 1, pageSize: 4 }).catch(() => ({ code: 500, data: {} })),
      salReturnApi.page({ pageNum: 1, pageSize: 4 }).catch(() => ({ code: 500, data: {} }))
    ])
    const rows = []
    ;(o.data?.records || []).forEach(x => rows.push({ no: x.billNo, party: x.customerName || '—', content: '销售订单', amount: x.totalAmount != null ? '¥ ' + x.totalAmount : '', status: STATUS_LABEL[x.billStatus] || x.billStatus }))
    ;(d.data?.records || []).forEach(x => rows.push({ no: x.billNo, party: x.customerName || '—', content: '销售出库', amount: x.totalAmount != null ? '¥ ' + x.totalAmount : '', status: STATUS_LABEL[x.billStatus] || x.billStatus }))
    ;(ret.data?.records || []).forEach(x => rows.push({ no: x.billNo, party: x.customerName || '—', content: '销售退货', amount: x.totalAmount != null ? '¥ ' + x.totalAmount : '', status: STATUS_LABEL[x.billStatus] || x.billStatus }))
    return rows
  },
  inventory: async () => {
    // v1.1.99: 与实际数据对接 — 改调 inv_ledger 台账明细 (单号/仓库/进出方向/数量),
    // 之前 stockApi.page 返回的库存汇总 + safetyStock 字段后端 SQL 没给, 预警判定永远为 false
    // (实测 128 条库存 + 479 条台账, Hub 表格只显示 3 条是因为字段名不匹配 camelCase/snake_case).
    const r = await (await import('@/api/inventory')).ledgerApi.page({ pageNum: 1, pageSize: 6 }).catch(() => ({ code: 500, data: {} }))
    const rows = []
    const directionLabel = (d) => d === 1 ? '入库' : d === -1 ? '出库' : '—'
    ;(r.data?.records || []).forEach(x => {
      const wh = x.warehouse_name || '未指定仓库'
      const dir = directionLabel(x.biz_direction)
      rows.push({
        no: x.bill_no || ('LED-' + x.id),
        party: x.product_name || '—',
        content: wh + ' / ' + dir,
        amount: x.qty != null ? String(x.qty) + ' ' + (x.unit_name || '') : '',
        status: x.biz_direction === 1 ? '充足' : x.biz_direction === -1 ? '预警' : '—'
      })
    })
    return rows
  },
  production: async () => {
    const r = await (await import('@/api/production')).prdOrderApi.page({ pageNum: 1, pageSize: 6 }).catch(() => ({ code: 500, data: {} }))
    const rows = []
    ;(r.data?.records || []).forEach(x => rows.push({ no: x.billNo, party: x.productName || '—', content: '生产加工单', amount: x.plannedQty != null ? String(x.plannedQty) : '', status: STATUS_LABEL[x.billStatus] || x.billStatus }))
    return rows
  },
  arap: async () => {
    const r = await arapApi.page({ pageNum: 1, pageSize: 6 }).catch(() => ({ code: 500, data: {} }))
    const rows = []
    ;(r.data?.records || []).forEach(x => {
      const isAr = x.customerName ? '客户应收' : '供应商应付'
      const party = x.customerName || x.supplierName || '—'
      const unpaid = x.amount != null && x.paidAmount != null ? (Number(x.amount) - Number(x.paidAmount)).toFixed(2) : ''
      rows.push({ no: x.billNo || ('ARAP-' + x.id), party, content: isAr, amount: unpaid !== '' ? '¥ ' + unpaid : '', status: STATUS_LABEL[x.invoiceStatus] || x.invoiceStatus || '—' })
    })
    return rows
  },
  report: async () => {
    // 报表分类: 无单据流, 聚合最近销售出库 + 库存预警作为"最新经营记录"
    const [d, inv] = await Promise.all([
      salDeliveryApi.page({ pageNum: 1, pageSize: 3 }).catch(() => ({ code: 500, data: {} })),
      (await import('@/api/inventory')).stockApi.page({ pageNum: 1, pageSize: 3 }).catch(() => ({ code: 500, data: {} }))
    ])
    const rows = []
    ;(d.data?.records || []).forEach(x => rows.push({ no: x.billNo, party: x.customerName || '—', content: '销售出库', amount: x.totalAmount != null ? '¥ ' + x.totalAmount : '', status: STATUS_LABEL[x.billStatus] || x.billStatus }))
    ;((inv.data?.records) || []).filter(x => x.safetyStock != null && Number(x.qty) < Number(x.safetyStock)).forEach(x => rows.push({ no: x.productCode || ('INV-' + x.productId), party: x.productName || '—', content: '库存预警', amount: x.qty != null ? String(x.qty) : '', status: '预警' }))
    return rows
  },
  system: async () => {
    const r = await (await import('@/api/system')).userApi.page({ pageNum: 1, pageSize: 6 }).catch(() => ({ code: 500, data: {} }))
    const rows = []
    ;(r.data?.records || []).forEach(x => rows.push({ no: x.username, party: x.nickname || x.realName || '—', content: '用户账号', amount: '', status: x.status === 1 ? '正常' : '停用' }))
    return rows
  }
}
// 真实数据 (随分类切换加载, 加载失败/无数据时表格显示 empty-text "暂无记录")
const recordsByCat = ref({})    // key=分类, value=行数组 (null=加载中)
const recLoading = ref(false)
const recQuery = ref('')
async function loadRecords(cat) {
  const loader = LOADERS[cat]
  if (!loader) { recordsByCat.value[cat] = []; return }
  recLoading.value = true
  try { recordsByCat.value[cat] = await loader() }
  catch (e) { recordsByCat.value[cat] = [] }
  finally { recLoading.value = false }
}
// 首次 + 分类切换按需加载 (已加载过则不重拉)
watch(activeCat, (c) => { if (!recordsByCat.value[c]) loadRecords(c) }, { immediate: true })

const filteredRecords = computed(() => {
  const list = recordsByCat.value[activeCat.value] || []
  const q = recQuery.value.trim().toLowerCase()
  if (!q) return list
  return list.filter(r => String(r.no || '').toLowerCase().includes(q) || String(r.party || '').toLowerCase().includes(q) || String(r.content || '').toLowerCase().includes(q))
})

function onLog() { router.push('/report/recycle') }
// 新建单据: 打开本分类第一个真实单据列表页 (用户在该页点"新增"), 比空弹更有用
function onNew() {
  const l = activeCatObj.value.links[0]
  if (l) router.push(l.path)
}
function onExport() {
  const list = recordsByCat.value[activeCat.value] || []
  if (!list.length) { ElMessage.warning('当前暂无可导出记录'); return }
  const head = ['单据编号', '往来单位', '业务内容', '金额/数量', '状态']
  const lines = list.map(r => [r.no, r.party, r.content, r.amount ? String(r.amount) : '', r.status].join('\t'))
  const csv = '﻿' + head.join('\t') + '\n' + lines.join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = activeCatObj.value.title + '-记录-' + new Date().toISOString().slice(0, 10) + '.csv'
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('已导出 ' + list.length + ' 条 ' + activeCatObj.value.title + ' 记录')
}
// 状态 pill 配色 (真实状态中文标签: 已审核/充足/正常=绿; 草稿/预警=橙; 停用/作废=灰; 已开票=绿)
function pillClass(status) {
  if (['已审核', '充足', '正常', '启用', '已开票', '部分开票'].includes(status)) return 'ok'
  if (['草稿', '预警', '未开票'].includes(status)) return 'amber'
  if (['停用', '已作废', '暂停供货'].includes(status) || status === '—') return 'muted'
  return 'ok'
}
function goLink(l) {
  // 路由不存在时 (未来若有新分类待接入), 给出友好提示而不让用户跳 404
  const exists = router.getRoutes().some(r => r.path === l.path)
  if (exists) router.push(l.path)
  else ElMessage.info(`「${l.title}」功能建设中, 请等待后续版本接入`)
}
</script>

<style scoped lang="scss">
.erp-hub { position: relative; }

.erp-hub-grid { display: flex; flex-direction: column; gap: 16px; margin-top: 4px; }

/* 左列分类卡已抽到全局 MainLayout/HubSidebar.vue (v1.1.92), 此处仅保留右栏样式 */

/* 右列 */
.erp-hub-right { display: flex; flex-direction: column; gap: 16px; }

.quick-card, .records-card {
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 18px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.quick-card-head, .records-card-head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px;
}
.quick-card-head b, .records-card-head b { font-size: 15px; font-weight: 730; color: var(--text); }
.quick-card-head small, .records-card-head small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
.records-actions { display: flex; align-items: center; gap: 10px; }

.quick-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.quick-grid.cols-4 { grid-template-columns: repeat(4, 1fr); }
.quick-grid.cols-5 { grid-template-columns: repeat(5, 1fr); }
@media (max-width: 1200px) {
  .quick-grid { grid-template-columns: repeat(2, 1fr); }
  .quick-grid.cols-4, .quick-grid.cols-5 { grid-template-columns: repeat(2, 1fr); }
}
.quick-item {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px; background: #fbfcfe; border: 1px solid #eef2f7;
  border-radius: 11px; cursor: pointer; transition: all .18s; font-size: 13px; font-weight: 550; color: var(--text);
  &:hover { background: var(--primary-color); color: #fff; border-color: var(--primary-color); }
  &:hover .qi-icon, &:hover .qi-arrow { color: #fff; }
}
.qi-icon { color: var(--primary-color); }
.qi-title { flex: 1; }
.qi-arrow { color: #b6c2d0; }

/* 状态 pill (浅绿/浅灰实心, 对齐 mockup) */
.rec-pill {
  display: inline-block; padding: 3px 12px; border-radius: 999px;
  font-size: 11px; font-weight: 700; line-height: 1.4;
  &.ok { background: #e6f7ec; color: #1f8a4c; }
  &.amber { background: #fff3e0; color: #c47a12; }
  &.muted { background: #eef1f5; color: #64748b; }
}

/* 经营 AI 助手 */
.ai-card {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  background: linear-gradient(90deg, #eef6ff, #f8fbff); border: 1px solid #bfd8f2;
  border-radius: 14px; padding: 18px 20px;
}
.ai-left { display: flex; align-items: center; gap: 14px; }
.ai-left b { display: block; font-size: 14px; font-weight: 700; color: #245986; }
.ai-left small { display: block; font-size: 11px; color: #5c7690; margin-top: 3px; }
</style>
