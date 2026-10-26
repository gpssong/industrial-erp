<template>
  <div class="app-shell">
    <div v-if="isMobile && mobileOpen" class="mobile-scrim" @click="mobileOpen = false"></div>

    <!-- 左侧深 navy 渐变 sidebar (Figma) -->
    <aside class="sidebar" :class="{ collapsed: collapse && !isMobile, 'is-mobile-open': isMobile && mobileOpen }">
      <div class="brand" @click="$router.push('/dashboard')">
        <span class="brand-symbol"><i/><i/><i/></span>
        <div v-if="!collapse || isMobile" class="brand-text">
          <strong>{{ systemName }}</strong>
          <small>ERP + MES</small>
        </div>
      </div>

      <!-- 左侧深 navy 渐变 sidebar (Figma 4 组: 经营总览/供应链/生产与质量/经营支持) -->
      <el-menu :default-active="route.path" :router="true" :collapse="collapse && !isMobile"
               class="side-nav" @select="onMenuSelect">
        <template v-for="g in menuTree" :key="g.group">
          <div class="nav-caption" v-if="!collapse || isMobile">{{ g.group }}</div>
          <template v-for="m in g.items" :key="m.path">
            <el-sub-menu v-if="m.children && m.children.length" :index="m.virtual ? 'virtual:' + m.path : m.path">
              <template #title>
                <el-icon><component :is="m.icon" /></el-icon>
                <span class="nav-label">{{ m.title }}</span>
                <el-badge v-if="m.badge" :value="m.badge" class="nav-badge" type="danger" />
                <i v-else class="nav-arrow" :class="{ open: isSubOpen(m) }"/>
              </template>
              <el-menu-item v-for="c in m.children" :key="c.path" :index="c.path">
                <el-icon><component :is="c.icon" /></el-icon>
                <template #title>{{ c.title }}</template>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="m.path">
              <el-icon><component :is="m.icon" /></el-icon>
              <template #title>
                <span class="nav-label">{{ m.title }}</span>
                <el-badge v-if="m.badge" :value="m.badge" class="nav-badge" type="danger" />
              </template>
            </el-menu-item>
          </template>
        </template>
      </el-menu>

      <div class="side-footer">
        <div class="side-factory">
          <span class="live-dot"/>
          <div><strong>{{ factoryName }}</strong><small>系统运行正常</small></div>
        </div>

        <div class="profile" @click="onProfileClick">
          <span class="avatar">{{ (userInfo?.nickname || userInfo?.username || '用').charAt(0) }}</span>
          <div class="profile-text">
            <strong>{{ userInfo?.nickname || userInfo?.username || '用户' }}</strong>
            <small>{{ (userInfo?.roles && userInfo.roles[0]) || '用户' }}</small>
          </div>
        <el-icon class="profile-more"><More /></el-icon>
        </div>
      </div>
    </aside>

    <!-- 主区 -->
    <div class="main-shell">
      <header class="topbar">
        <button class="menu-button" @click="onCollapseClick" :aria-label="'打开菜单'">
          <el-icon><component :is="collapse ? 'Expand' : 'Fold'" /></el-icon>
        </button>
        <div class="mobile-current">
          <span>{{ currentTitle }}</span>
          <small>华东一厂 · 早班</small>
        </div>
        <div class="global-search">
          <el-icon><Search /></el-icon>
          <input placeholder="搜索工单、批次、设备…" />
          <kbd>⌘ K</kbd>
        </div>
        <div class="top-actions">
          <span class="shift"><i/> 早班 08:00–16:00</span>
          <el-dropdown @command="onCommand" trigger="click">
            <button class="icon-button bell" aria-label="消息"><el-icon><Bell /></el-icon><b/></button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="changePassword"><el-icon><Key /></el-icon>修改密码</el-dropdown-item>
                <el-dropdown-item command="logout" divided><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <div class="content-shell" :class="{ 'with-hub': showHubSidebar }">
          <!-- v1.1.92: 购销存管理 — 左侧分类卡常驻 (Figma image #18+#20) -->
          <HubSidebar v-if="showHubSidebar" />
          <div class="content-main">
            <router-view v-slot="{ Component }">
              <component :is="Component" :key="$route.fullPath" />
            </router-view>
          </div>
        </div>
      </main>
    </div>
  </div>

  <!-- 修改密码弹窗 -->
  <el-dialog v-model="pwdDialogVisible" title="修改密码" width="420px" destroy-on-close>
    <el-form :model="pwdForm" label-width="90px" ref="pwdFormRef">
      <el-form-item label="原密码" prop="oldPassword">
        <el-input v-model="pwdForm.oldPassword" type="password" show-password clearable />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="pwdForm.newPassword" type="password" show-password clearable />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input v-model="pwdForm.confirmPassword" type="password" show-password clearable />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="pwdDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="submitChangePassword" :loading="pwdSubmitting">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Key, SwitchButton, More, Search, Bell } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'
import { useSystemName } from '@/composables/useSystemName'
import HubSidebar from '@/components/HubSidebar.vue'
import { userApi } from '@/api/system'
import { xkMock } from '@/api/xk'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const collapse = ref(false)
const isMobile = ref(false)
const mobileOpen = ref(false)
// 设备维保 badge = 实时「待处理维保工单」数 (GET /xk/dashboard → compliance.maintainPending)
// 0 时不显示; 非管理员无 xk 权限则不请求, 保持 0 → 不渲染
const maintainBadge = ref(0)
const { systemName, factoryName, loadSystemName } = useSystemName()

// 顶栏移动端当前页标题 (取当前 route 命中菜单的 title)
const currentTitle = computed(() => {
  const m = route.matched.filter(r => r.meta && r.meta.title)
  return m.length ? m[m.length - 1].meta.title : '工作台'
})

function onProfileClick() { pwdDialogVisible.value = true }

const userInfo = computed(() => userStore.userInfo)

// 用户有权限的菜单路径集合 (从后端 menus 提取)
const allowedPaths = computed(() => {
  const paths = new Set()
  const menus = userStore.menus || []
  menus.forEach(m => {
    if (m.path) paths.add(m.path)
  })
  return paths
})

// 是否为管理员 (管理员看到所有菜单)
const isAdmin = computed(() => {
  const u = userStore.userInfo
  if (!u) return false
  if (u.userId === 1 || u.userId === '1' || u.userId === 0 || u.userId === '0') return true
  if (u.isAdmin === 1 || u.isAdmin === true) return true
  return (u.roles || []).includes('SUPER_ADMIN')
})

// 根据权限过滤菜单树 — 按 Figma mockup 分 4 组: 经营总览 / 供应链 / 生产与质量 / 经营支持
// 原 ERP 模块统一收进「购销存管理」子菜单 (供应链组), 其余为 XK/MES 扁平页
const menuTree = computed(() => {
  const raw = [
    {
      group: '经营总览', items: [
        { path: '/dashboard', title: '经营驾驶舱', icon: 'Odometer' }
      ]
    },
    {
      group: '供应链', items: [
        // 购销存管理 = hub 页, 聚合全部原 ERP 模块入口 (Figma 布局, 见 views/erp/Hub.vue)
        { path: '/erp', title: '购销存管理', icon: 'Document' },
        { path: '/xk/material', title: '合格供应商', icon: 'CircleCheck' },
        { path: '/purchase/receipt', title: '采购入库', icon: 'Box' },
        { path: '/xk/trace', title: '库存与追溯', icon: 'Promotion' }
      ]
    },
    {
      group: '生产与质量', items: [
        { path: '/production/order', title: '生产工单', icon: 'SetUp' },
        { path: '/production/requisition', title: '领料记录', icon: 'Notebook' },
        { path: '/production/finished-in', title: '成品入库', icon: 'Box' },
        { path: '/production/semi-in', title: '半成品入库', icon: 'Box' },
        { path: '/production/schedule', title: '生产排程', icon: 'DataLine' },
        { path: '/xk/maintenance', title: '设备维保', icon: 'Tools', badge: maintainBadge.value || undefined },
        { path: '/xk/safety', title: '质量与XK检查', icon: 'CircleCheck' },
        { path: '/report/inspection', title: '检验台账', icon: 'List' }
      ]
    },
    {
      group: '经营支持', items: [
        { path: '/xk/print', title: '单据与打印', icon: 'Printer' },
        { path: '/xk/audit', title: '审计归档', icon: 'Clock' }
      ]
    }
  ]

  // 过滤单个节点 (父菜单或叶子)
  const filterNode = (m) => {
    if (m.children && m.children.length) {
      const children = m.children.filter(c => (c.children ? filterNode(c) : allowedPaths.value.has(c.path)))
      return children.length ? { ...m, children } : null
    }
    return allowedPaths.value.has(m.path) ? m : null
  }

  // 管理员看到全部 (原样返回 groups)
  if (isAdmin.value) return raw

  // 普通用户: 按后端 menus 过滤, 空组 (items 全被滤掉) 隐藏
  return raw
    .map(g => ({ ...g, items: g.items.map(filterNode).filter(Boolean) }))
    .filter(g => g.items.length > 0)
})

// v1.1.92: 购销存管理 — 业务子路由下显示持久左列 (Figma image #18+#20)
const showHubSidebar = computed(() => {
  const p = route.path
  if (p === '/erp') return true
  return /^\/(base|purchase|sales|inventory|production|finance|report|system|xk\/(material|trace))/.test(p)
})

// el-sub-menu 展开状态 (供箭头 icon 用): 默认 ERP 子菜单展开
const subOpen = ref({ '/erp': true })
const isSubOpen = (m) => subOpen.value[m.virtual ? 'virtual:' + m.path : m.path] !== false

function checkMobile() {
  isMobile.value = window.innerWidth <= 768
  if (isMobile.value) {
    collapse.value = false  // 手机上不用 collapse 模式,改用抽屉
    mobileOpen.value = false
  }
}

function onCollapseClick() {
  if (isMobile.value) {
    mobileOpen.value = !mobileOpen.value
  } else {
    collapse.value = !collapse.value
  }
}

function onMenuSelect() {
  // 手机上选中菜单后自动关闭抽屉
  if (isMobile.value) mobileOpen.value = false
}

async function onCommand(cmd) {
  if (cmd === 'logout') {
    await userStore.logoutAction()
    router.push('/login')
  } else if (cmd === 'changePassword') {
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
    pwdDialogVisible.value = true
  }
}

// 修改密码
const pwdDialogVisible = ref(false)
const pwdSubmitting = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdFormRef = ref()

async function submitChangePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请输入原密码和新密码')
    return
  }
  // #92: 密码复杂度校验 — 8位+字母+数字
  if (pwdForm.newPassword.length < 8) {
    ElMessage.warning('新密码至少 8 位')
    return
  }
  if (!/[a-zA-Z]/.test(pwdForm.newPassword)) {
    ElMessage.warning('新密码必须包含至少一个字母')
    return
  }
  if (!/\d/.test(pwdForm.newPassword)) {
    ElMessage.warning('新密码必须包含至少一个数字')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  pwdSubmitting.value = true
  try {
    await userApi.changeOwnPassword(pwdForm.oldPassword, pwdForm.newPassword)
    ElMessage.success('密码修改成功, 请用新密码重新登录')
    pwdDialogVisible.value = false
    // 修改密码后强制登出, 让用户重新登录
    setTimeout(async () => {
      await userStore.logoutAction()
      router.push('/login')
    }, 800)
  } catch (e) {
    ElMessage.error(e.message || '密码修改失败')
  } finally {
    pwdSubmitting.value = false
  }
}

// 设备维保 badge: 拉 XK dashboard 的「待处理维保工单」总数 (compliance.maintainPending).
// 非管理员不请求 (无 xk 权限 → 保持 0 不渲染); 请求失败静默降级 0, 不影响布局.
async function loadMaintainBadge() {
  if (!isAdmin.value) { maintainBadge.value = 0; return }
  try {
    const r = await xkMock.dashboardKpi()
    maintainBadge.value = Number(r?.data?.pendingMaintain?.value ?? 0)
  } catch (e) {
    maintainBadge.value = 0
  }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try { await userStore.fetchMe() } catch (e) {}
  }
  loadSystemName()
  checkMobile()
  window.addEventListener('resize', checkMobile)
  loadMaintainBadge()
  // #85: 默认密码拦截 — passwordExpired=true 时强制弹改密框, 阻止进入系统
  if (userStore.userInfo?.passwordExpired) {
    ElMessage.warning('默认密码已过期, 请立即修改密码')
    pwdDialogVisible.value = true
  }
})

// #85: 监听登录态变化, 每次刷新页面重新检测 passwordExpired
watch(() => userStore.userInfo?.passwordExpired, (expired) => {
  if (expired) {
    ElMessage.warning('默认密码已过期, 请立即修改密码')
    pwdDialogVisible.value = true
  }
})

// 管理员登录态就绪后拉一次维保 badge (覆盖 onMounted 时 userInfo 尚未返回的时序)
watch(isAdmin, (adm) => { if (adm) loadMaintainBadge() })

onUnmounted(() => {
  window.removeEventListener('resize', checkMobile)
})
</script>

<style scoped lang="scss">
// =====================================================================
// Figma "ERP MES Management System" 视觉 — 全局壳 (sidebar + topbar + content)
// 深 navy 渐变 sidebar / 白 topbar + 全局搜索·班次·铃铛 / 浅灰 canvas
// =====================================================================
.app-shell { min-height: 100vh; display: flex; }

// —— sidebar: 深 navy 渐变 + brand + 深底 el-menu ——
.sidebar {
  width: 252px;
  background: linear-gradient(180deg, var(--menu-bg) 0%, var(--menu-bg-deep) 100%);
  color: #dbe8f5;
  padding: 24px 16px 16px;
  position: fixed; inset: 0 auto 0 0;
  display: flex; flex-direction: column;
  z-index: 40;
  &.collapsed { width: 84px; }
  &.is-mobile-open { transform: none; box-shadow: 20px 0 40px rgba(6, 24, 42, .2); }
  transition: width .28s ease, transform .25s ease;
}
.brand {
  display: flex; align-items: center; gap: 12px;
  padding: 0 12px 24px; color: #fff; cursor: pointer;
}
.brand-symbol {
  display: inline-flex; width: 38px; height: 38px; padding: 8px; gap: 3px;
  align-items: flex-end; border-radius: 11px; background: var(--primary-color);
  flex: 0 0 auto;
  i { flex: 1; background: #fff; border-radius: 2px 2px 0 0; transform: skewY(-18deg); }
  i:nth-child(1) { height: 50%; } i:nth-child(2) { height: 72%; } i:nth-child(3) { height: 94%; }
}
.brand-text { display: flex; flex-direction: column; }
.brand-text strong { font-size: 17px; letter-spacing: .04em; font-weight: 750; }
.brand-text small { color: #8ca5bd; font-size: 10px; letter-spacing: .16em; margin-top: 2px; }

.nav-caption { padding: 10px 13px 6px; color: #6f8ba7; font-size: 11px; letter-spacing: .16em;
  &:not(:first-child) { margin-top: 6px; border-top: 1px solid rgba(255,255,255,.06); padding-top: 12px; }
}

.side-nav {
  flex: 1; overflow-y: auto; border: 0 !important;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #aebfd0;
  --el-menu-active-color: #fff;
  background: transparent !important;
  .el-menu-item, .el-sub-menu__title {
    height: 44px; line-height: 44px; margin: 2px 0; border-radius: 9px;
    color: #aebfd0 !important; font-size: 13.5px;
    &:hover { background: rgba(255, 255, 255, .06); color: #fff !important; }
  }
  .el-menu-item.is-active {
    background: var(--menu-active) !important; color: #fff !important;
    box-shadow: 0 8px 22px rgba(0, 84, 174, .35);
  }
  .el-menu-item.is-active + .el-sub-menu .el-menu-item, .is-active { border-left: 0; }
  :deep(.el-menu-item), :deep(.el-sub-menu__title) {
    border-left: 0 !important;
  }
  :deep(.el-sub-menu .el-menu-item) { min-height: 40px; line-height: 40px; background: transparent; }
  :deep(.el-sub-menu .el-menu-item.is-active) { background: var(--menu-active) !important; }
  :deep(.el-sub-menu__title:hover), :deep(.el-menu-item:hover) { background: rgba(255,255,255,.06); }
  // 标签 + badge + 折叠箭头
  .nav-label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .el-sub-menu__title { position: relative; display: flex; align-items: center; gap: 10px; }
  // 叶子菜单项: el-menu-item 与 el-menu-item__title 均设 flex, badge 跟随 label 同行右侧
  .el-menu-item { position: relative; display: flex; align-items: center; }
  .el-menu-item :deep(.el-menu-item__title) { flex: 1; display: flex; align-items: center; gap: 10px; min-width: 0; }
  .nav-arrow {
    width: 0; height: 0; border-left: 5px solid rgba(255,255,255,.45);
    border-top: 4px solid transparent; border-bottom: 4px solid transparent;
    margin-left: auto; transition: transform .18s;
    &.open { transform: rotate(90deg); }
  }
  .nav-badge { margin-left: auto; }
  .nav-badge .el-badge__content {
    background: #f0443a !important; color: #fff; border: 0;
    box-shadow: 0 0 0 3px rgba(240,68,58,.2);
  }
}

.side-factory {
  margin: 12px 3px; padding: 13px;
  border: 1px solid rgba(255,255,255,.08); background: rgba(255,255,255,.04);
  border-radius: 10px; display: flex; align-items: center; gap: 9px;
}
// 底部固定 footer (工厂 + 用户), 菜单区在其上方独立滚动, 子菜单展开不再被 footer 遮住
.side-footer { flex: 0 0 auto; margin-top: auto; }
.live-dot { width: 8px; height: 8px; border-radius: 50%; background: #42d09a; box-shadow: 0 0 0 4px rgba(66,208,154,.12); flex: 0 0 auto; }
.side-factory div { flex: 1; }
.side-factory strong { display: block; font-size: 12px; color: #dbe8f5; }
.side-factory small { display: block; font-size: 10px; color: #7f9ab4; margin-top: 2px; }

.profile {
  border: 0; background: transparent; color: #fff;
  padding: 12px 8px 0; border-top: 1px solid rgba(255,255,255,.08);
  display: flex; align-items: center; gap: 10px; text-align: left; cursor: pointer;
}
.profile .avatar {
  width: 35px; height: 35px; display: grid; place-items: center;
  border-radius: 9px; background: #33516e; font-size: 14px; font-weight: 700; flex: 0 0 auto;
}
.profile-text { flex: 1; min-width: 0; }
.profile-text strong { display: block; font-size: 13px; }
.profile-text small { display: block; color: #839bb2; font-size: 10px; margin-top: 2px; }
.profile-more { color: #839bb2; }

// —— 主区 + 顶栏 ——
.main-shell { margin-left: 252px; width: calc(100% - 252px); min-height: 100vh; display: flex; flex-direction: column; }
.sidebar.collapsed ~ .main-shell, .sidebar.collapsed + .main-shell { margin-left: 84px; width: calc(100% - 84px); }

.topbar {
  height: 68px; padding: 0 28px;
  background: rgba(255,255,255,.93); border-bottom: 1px solid var(--line);
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  position: sticky; top: 0; z-index: 30; backdrop-filter: blur(12px);
}
.menu-button {
  border: 0; background: #f5f7fa; color: #617084; border-radius: 9px;
  width: 38px; height: 38px; display: grid; place-items: center; flex: 0 0 auto;
  &:hover { background: #eaeef3; color: var(--primary-color); }
}
.mobile-current { display: none; }
.global-search {
  display: flex; align-items: center; gap: 10px; color: #8a96a6;
  width: min(420px, 45%); padding: 0 4px;
  .el-icon { flex: 0 0 auto; }
  input { border: 0; outline: 0; background: transparent; flex: 1; font-size: 13px; color: var(--text); }
  kbd { padding: 3px 7px; background: #f0f3f7; border: 1px solid #e1e7ee; border-radius: 5px; color: #8994a2; font-size: 10px; font-family: inherit; }
}
.top-actions { display: flex; align-items: center; gap: 12px; }
.shift {
  color: var(--muted); font-size: 12px; border-right: 1px solid var(--line); padding-right: 16px;
  display: inline-flex; align-items: center; white-space: nowrap;
  i { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--success); margin-right: 6px; }
}
.icon-button {
  border: 0; background: #f5f7fa; color: #617084; border-radius: 9px;
  width: 38px; height: 38px; display: grid; place-items: center; position: relative;
  &:hover { background: #eaeef3; color: var(--primary-color); }
  &.bell b { position: absolute; width: 7px; height: 7px; background: var(--danger); border: 2px solid #fff; border-radius: 50%; right: 8px; top: 8px; }
}

.content { flex: 1; padding: 24px 28px 60px; width: 100%; max-width: 1560px; margin: 0 auto; }

// v1.1.92: 购销存管理常驻左列布局 — 当 HubSidebar 显示时, 让 content 自适应两栏
.content-shell {
  display: block;
}
.content-shell.with-hub {
  display: flex;
  align-items: flex-start;
  gap: 20px;
}
.content-shell.with-hub .content-main { flex: 1; min-width: 0; }
@media (max-width: 1050px) {
  .content-shell.with-hub { flex-direction: column; gap: 14px; }
}


.mobile-scrim {
  display: none;
}

// —— 响应式: 移动端抽屉 sidebar ——
@media (max-width: 1050px) {
  .sidebar { width: 230px; }
  .main-shell { margin-left: 230px; width: calc(100% - 230px); }
}
@media (max-width: 768px) {
  .sidebar { width: 278px; transform: translateX(-100%); }
  .sidebar.is-mobile-open { transform: none; }
  .sidebar.collapsed { width: 278px; }
  .main-shell { margin: 0; width: 100%; }
  .mobile-scrim {
    display: block; position: fixed; inset: 0; background: rgba(5, 19, 32, .42); z-index: 35;
  }
  .topbar { height: 62px; padding: 0 16px; }
  .mobile-current { display: flex; flex-direction: column; margin-left: 6px; flex: 1; }
  .mobile-current span { font-size: 14px; font-weight: 750; color: var(--text); }
  .mobile-current small { color: var(--muted); font-size: 10px; margin-top: 2px; }
  .global-search, .shift { display: none; }
}
</style>
