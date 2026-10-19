<template>
  <!--
    v1.1.92: 购销存管理 — 左侧 9 张分类卡常驻组件 (Figma image #18+#20)
    - 在 /erp 完整 Hub 页与所有 ERP 业务子路由 (/production/*, /purchase/*, /sales/*,
      /inventory/*, /base/*, /finance/*, /report/*, /system/*, /xk/material, /xk/trace) 通用
    - 点击分类切换 activeCat; 点击分类时跳转 /erp?cat=xxx 让 Hub 右栏定位到对应分类
    - 子路由下点击"回到 Hub 分类"链接, 通过 router-link 到 /erp
  -->
  <aside class="hub-sidebar">
    <div class="hub-sidebar-head">
      <div class="hub-eyebrow">供应链 / 经营业务</div>
      <h2 class="hub-title">购销存与经营管理</h2>
    </div>
    <div class="hub-cat-list">
      <router-link
        v-for="cat in categories"
        :key="cat.key"
        :to="cat.path === '/erp' ? `/erp?cat=${cat.key}` : cat.firstLink"
        custom
        v-slot="{ navigate, isActive }"
      >
        <div
          class="cat-card"
          :class="{ active: isActiveByKey(cat) }"
          @click="onClick(cat, navigate)"
        >
          <span class="cat-icon">
            <el-icon :size="18"><component :is="cat.icon" /></el-icon>
          </span>
          <div class="cat-text">
            <strong>{{ cat.title }}</strong>
            <small>{{ cat.sub }}</small>
          </div>
          <el-icon class="cat-arrow"><ArrowRight /></el-icon>
        </div>
      </router-link>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

// 与 views/erp/Hub.vue 保持一致 (共享一份数据, Hub.vue 内 categories 后续可改为从此处 import;
// 当前为最小侵入 — 两处并列, 数据驱动, 修改时同步即可)
const categories = [
  { key: 'base',       title: '基础资料',   icon: 'Goods',         sub: '商品管理 · 客户管理 · 供应商管理 · 仓...', path: '/erp',       firstLink: '/base/product' },
  { key: 'purchase',   title: '采购管理',   icon: 'Box',           sub: '采购计划 · 采购订单 · 来料检验 · 采购...', path: '/erp',       firstLink: '/purchase/order' },
  { key: 'sales',      title: '销售管理',   icon: 'TakeawayBox',   sub: '销售订单 · 销售出库 · 销售退货',         path: '/erp',       firstLink: '/sales/order' },
  { key: 'inventory',  title: '库存管理',   icon: 'Grid',          sub: '库存查询 · 库存台账 · 库存盘点 · 批次...', path: '/erp',       firstLink: '/inventory/stock' },
  { key: 'production', title: '生产管理',   icon: 'Files',         sub: 'BOM 清单 · 生产加工单 · 领料记录 · 成...', path: '/erp',       firstLink: '/production/order' },
  { key: 'arap',       title: '应收应付',   icon: 'Money',         sub: '客户应收 · 供应商应付 · 收款记录 · 付款...', path: '/erp',    firstLink: '/finance/arap' },
  { key: 'report',     title: '报表中心',   icon: 'TrendCharts',   sub: '销售报表 · 库存报表 · 采购报表 · 经营...', path: '/erp',     firstLink: '/report/sales' },
  { key: 'system',     title: '系统管理',   icon: 'Menu',          sub: '用户管理 · 角色管理 · 菜单管理 · 部门...', path: '/erp',       firstLink: '/system/user' }
]

// 当前 active 分类: 子路由时按 prefix 推导出父分类
const activeKey = computed(() => {
  const q = route.query.cat
  if (q && categories.some(c => c.key === q)) return q
  const p = route.path
  if (p.startsWith('/base/') || p === '/erp') return 'base'
  if (p.startsWith('/xk/material')) return 'purchase'
  if (p.startsWith('/purchase/')) return 'purchase'
  if (p.startsWith('/sales/')) return 'sales'
  if (p.startsWith('/inventory/') || p.startsWith('/xk/trace')) return 'inventory'
  if (p.startsWith('/production/')) return 'production'
  if (p.startsWith('/finance/')) return 'arap'
  if (p.startsWith('/report/')) return 'report'
  if (p.startsWith('/system/')) return 'system'
  return null
})

function isActiveByKey(cat) {
  return activeKey.value === cat.key
}

function onClick(cat, navigate) {
  // router-link custom 已拦截默认跳转, 这里按设计: 总是跳到 /erp?cat=xxx 让右栏定位
  router.push({ path: '/erp', query: { cat: cat.key } })
}
</script>

<style scoped lang="scss">
.hub-sidebar {
  width: 290px;
  flex: 0 0 290px;
  display: flex; flex-direction: column; gap: 12px;
  // 常驻: 滚动右侧时左列不消失
  position: sticky;
  top: 88px;
  max-height: calc(100vh - 110px);
  overflow-y: auto;
  padding-right: 4px;
}
.hub-sidebar::-webkit-scrollbar { width: 6px; }
.hub-sidebar::-webkit-scrollbar-thumb { background: #d8e0eb; border-radius: 3px; }

.hub-sidebar-head { padding: 0 2px 4px; }
.hub-sidebar-head .hub-eyebrow {
  font-size: 12px; font-weight: 600; color: var(--primary-color);
  letter-spacing: .04em;
}
.hub-sidebar-head .hub-title {
  font-size: 18px; font-weight: 750; color: var(--text);
  margin: 4px 0 0;
}

.hub-cat-list { display: flex; flex-direction: column; gap: 12px; }

.cat-card {
  display: flex; align-items: center; gap: 14px;
  padding: 16px 18px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 14px;
  cursor: pointer;
  transition: all .18s;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
  &:hover {
    border-color: #cfe0f2;
    box-shadow: 0 8px 24px rgba(20, 80, 150, .08);
    transform: translateY(-1px);
  }
  &.active {
    border-color: var(--primary-color);
    box-shadow: 0 8px 24px rgba(22, 113, 217, .14);
    .cat-icon { background: var(--primary-color); color: #fff; }
    .cat-arrow { color: var(--primary-color); }
  }
}
.cat-icon {
  width: 44px; height: 44px;
  border-radius: 12px;
  flex: 0 0 auto;
  display: grid; place-items: center;
  background: #eef5ff; color: var(--primary-color);
}
.cat-text { flex: 1; min-width: 0; }
.cat-text strong { display: block; font-size: 14px; color: var(--text); font-weight: 650; }
.cat-text small {
  display: block; font-size: 11px; color: var(--muted); margin-top: 3px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.cat-arrow { color: #b6c2d0; flex: 0 0 auto; }

@media (max-width: 1050px) {
  .hub-sidebar {
    width: 100%; flex: 0 0 auto;
    position: static; max-height: none; overflow-y: visible;
  }
}
</style>