<template>
  <el-container class="layout-container">
    <el-aside :width="collapse ? '64px' : '220px'" class="sidebar">
      <div class="logo" :class="{ collapsed: collapse }">
        <span v-if="!collapse">🏭 工业ERP</span>
        <span v-else>ERP</span>
      </div>
      <el-menu :default-active="route.path" :router="true" :collapse="collapse"
               background-color="#001529" text-color="#dcdcdc"
               active-text-color="#fff">
        <template v-for="m in menuTree" :key="m.path">
          <el-sub-menu v-if="m.children && m.children.length" :index="m.path">
            <template #title>
              <el-icon><component :is="m.icon" /></el-icon>
              <span>{{ m.title }}</span>
            </template>
            <el-menu-item v-for="c in m.children" :key="c.path" :index="c.path">
              <el-icon><component :is="c.icon" /></el-icon>
              <template #title>{{ c.title }}</template>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="m.path">
            <el-icon><component :is="m.icon" /></el-icon>
            <template #title>{{ m.title }}</template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-icon class="collapse-btn" @click="collapse = !collapse">
            <component :is="collapse ? 'Expand' : 'Fold'" />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-for="b in breadcrumbs" :key="b.path">{{ b.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <span class="company">{{ userInfo?.nickname || '用户' }}</span>
          <el-dropdown @command="onCommand">
            <span class="user-trigger">
              <el-avatar :size="28">{{ (userInfo?.nickname || 'U').charAt(0) }}</el-avatar>
              <span style="margin-left:8px">{{ userInfo?.nickname || userInfo?.username }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const collapse = ref(false)

const userInfo = computed(() => userStore.userInfo)

// 菜单结构 (与 router meta 对应)
const menuTree = [
  { path: '/dashboard', title: '工作台', icon: 'Odometer' },
  {
    path: '/system', title: '系统管理', icon: 'Setting',
    children: [
      { path: '/system/user', title: '用户管理', icon: 'User' },
      { path: '/system/role', title: '角色管理', icon: 'UserFilled' },
      { path: '/system/menu', title: '菜单管理', icon: 'Menu' },
      { path: '/system/dept', title: '部门管理', icon: 'OfficeBuilding' },
      { path: '/system/settings', title: '系统设置', icon: 'Setting' }
    ]
  },
  {
    path: '/base', title: '基础资料', icon: 'Goods',
    children: [
      { path: '/base/product', title: '商品管理', icon: 'Goods' },
      { path: '/base/customer', title: '客户管理', icon: 'Avatar' },
      { path: '/base/supplier', title: '供应商管理', icon: 'Connection' },
      { path: '/base/warehouse', title: '仓库管理', icon: 'House' },
      { path: '/base/unit', title: '计量单位', icon: 'DataLine' }
    ]
  },
  {
    path: '/purchase', title: '采购管理', icon: 'List',
    children: [
      { path: '/purchase/order', title: '采购订单', icon: 'List' },
      { path: '/purchase/receipt', title: '采购入库', icon: 'Box' }
    ]
  },
  {
    path: '/sales', title: '销售管理', icon: 'Sell',
    children: [
      { path: '/sales/order', title: '销售订单', icon: 'Tickets' },
      { path: '/sales/delivery', title: '销售出库', icon: 'TakeawayBox' }
    ]
  },
  {
    path: '/inventory', title: '库存管理', icon: 'Grid',
    children: [
      { path: '/inventory/stock', title: '库存查询', icon: 'Grid' },
      { path: '/inventory/ledger', title: '库存台账', icon: 'Notebook' }
    ]
  },
  {
    path: '/production', title: '生产管理', icon: 'SetUp',
    children: [
      { path: '/production/bom', title: 'BOM清单', icon: 'Files' },
      { path: '/production/order', title: '生产加工单', icon: 'SetUp' }
    ]
  },
  { path: '/finance/arap', title: '应收应付', icon: 'Money' },
  {
    path: '/report', title: '报表中心', icon: 'DataAnalysis',
    children: [
      { path: '/report/sales', title: '销售报表', icon: 'TrendCharts' },
      { path: '/report/inventory', title: '库存报表', icon: 'PieChart' }
    ]
  }
]

const breadcrumbs = computed(() => {
  const m = route.matched.filter(r => r.meta && r.meta.title)
  return m
})

async function onCommand(cmd) {
  if (cmd === 'logout') {
    await userStore.logoutAction()
    router.push('/login')
  }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.fetchMe() } catch (e) {}
  }
})
</script>

<style scoped lang="scss">
.layout-container { height: 100%; }
.sidebar {
  background: #001529; transition: width 0.3s;
  .logo { height: 60px; color: #fff; display: flex; justify-content: center; align-items: center;
          font-size: 18px; font-weight: bold; border-bottom: 1px solid #1f2d3d; }
  :deep(.el-menu) { border-right: none; }
}
.header { background: #fff; box-shadow: 0 1px 4px rgba(0,21,41,0.08);
  display: flex; justify-content: space-between; align-items: center; padding: 0 16px;
  .header-left { display: flex; align-items: center; gap: 16px; .collapse-btn { font-size: 18px; cursor: pointer; } }
  .header-right { display: flex; align-items: center; gap: 16px;
    .user-trigger { display: flex; align-items: center; cursor: pointer; } }
  .company { color: #888; font-size: 12px; }
}
.main-content { background: #f0f2f5; padding: 12px; }
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
