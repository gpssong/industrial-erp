<template>
  <div class="sys-settings">
    <!-- 页面标题 + 返回/保存 (对齐 Figma 系统设置页) -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">购销存管理 / 系统管理</div>
        <h1 class="xk-title">系统设置</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="router.push('/system/user')"><el-icon><ArrowLeft /></el-icon>返回系统管理</el-button>
        <el-button type="primary" @click="onSave"><el-icon><Check /></el-icon>保存设置</el-button>
      </div>
    </div>

    <div class="sys-grid">
      <!-- 左: 功能分区卡 -->
      <nav class="sys-nav">
        <div
          v-for="t in tabs"
          :key="t.key"
          class="sys-nav-item"
          :class="{ active: t.key === activeTab }"
          @click="activeTab = t.key"
        >
          <span class="sys-nav-icon"><el-icon :size="18"><component :is="t.icon" /></el-icon></span>
          <div class="sys-nav-text">
            <strong>{{ t.title }}</strong>
            <small>{{ t.sub }}</small>
          </div>
          <el-icon class="sys-nav-arrow"><ArrowRight /></el-icon>
        </div>
      </nav>

      <!-- 右: 当前分区内容 (复用现有子组件, 各自带保存) -->
      <section class="sys-content">
        <div v-if="activeTab === 'config'" class="sys-config">
          <!-- 卡片 1: 系统信息 (Figma 8 字段网格) -->
          <div class="cfg-card">
            <div class="cfg-card-head">
              <div><b>系统信息</b><small>当前部署版本与运行环境</small></div>
              <span class="svc-tag ok">运行正常</span>
            </div>
            <div class="info-grid">
              <div class="info-cell"><small>系统名称</small><b>{{ systemName }}</b></div>
              <div class="info-cell"><small>当前版本</small><b>{{ frontendVersion }}</b></div>
              <div class="info-cell"><small>构建版本</small><b>{{ frontendBuildTime }}</b></div>
              <div class="info-cell"><small>部署方式</small><b>私有化部署</b></div>
              <div class="info-cell"><small>数据库</small><b>{{ backendInfo.db || 'MySQL 8.0' }}</b></div>
              <div class="info-cell"><small>运行环境</small><b>{{ backendInfo.java ? 'JDK ' + backendInfo.java : 'Spring Boot' }}</b></div>
              <div class="info-cell"><small>服务器时间</small><b>{{ serverTime }}</b></div>
              <div class="info-cell"><small>授权状态</small><b>企业正式版 · 永久授权</b></div>
            </div>
          </div>

          <!-- 卡片 2: 基础参数 -->
          <div class="cfg-card">
            <div class="cfg-card-head"><div><b>基础参数</b><small>修改后对所有用户生效</small></div></div>
            <el-form label-position="top">
              <div class="brand-grid">
                <el-form-item label="系统名称"><el-input v-model="cfg.systemName" placeholder="精铸智造 ERP+MES" /></el-form-item>
                <el-form-item label="默认语言">
                  <el-select v-model="cfg.lang"><el-option label="简体中文" value="zh-CN" /><el-option label="English" value="en" /></el-select>
                </el-form-item>
                <el-form-item label="默认时区">
                  <el-select v-model="cfg.timezone"><el-option label="Asia/Shanghai (UTC+8)" value="Asia/Shanghai" /><el-option label="UTC" value="UTC" /><el-option label="Europe/Paris" value="Europe/Paris" /></el-select>
                </el-form-item>
                <el-form-item label="单据编号前缀"><el-input v-model="cfg.billPrefix" placeholder="JZ" /></el-form-item>
                <el-form-item label="数据保留期限">
                  <el-select v-model="cfg.retention"><el-option label="永久保留" value="forever" /><el-option label="1 年" value="1y" /><el-option label="3 年" value="3y" /></el-select>
                </el-form-item>
                <el-form-item label="会话超时时间">
                  <el-select v-model="cfg.sessionTimeout"><el-option label="30 分钟" value="30" /><el-option label="1 小时" value="60" /><el-option label="8 小时" value="480" /></el-select>
                </el-form-item>
                <div class="toggle-row"><div><b>启用操作审计</b><small>记录所有新增、修改、作废和导出操作</small></div><el-checkbox v-model="cfg.audit" /></div>
                <div class="toggle-row"><div><b>启用 XK 强制校验</b><small>不满足质量条件时禁止入库或生产</small></div><el-checkbox v-model="cfg.xkCheck" /></div>
              </div>
            </el-form>
            <div class="cfg-foot"><el-button type="primary" @click="saveConfig">保存基础参数</el-button></div>
          </div>

          <!-- 卡片 3: 既有完整 Settings (系统名称设置 + 系统信息 + 配置列表) -->
          <Settings />
        </div>
        <div v-else-if="activeTab === 'brand'" class="sys-brand">
          <div class="brand-card">
            <div class="brand-card-head"><b>品牌与工厂信息</b><small>用于登录页、系统导航和打印单据</small></div>

            <div class="brand-body">
              <!-- 左: Logo 上传区 -->
              <div class="brand-logo-col">
                <div class="logo-drop" @click="triggerLogoUpload" @dragover.prevent @drop.prevent="onLogoDrop">
                  <div v-if="logoPreview" class="logo-preview">
                    <img :src="logoPreview" alt="Logo" />
                  </div>
                  <div v-else class="logo-empty"><el-icon :size="30"><OfficeBuilding /></el-icon></div>
                  <small>{{ logoPreview ? '点击更换 Logo' : '点击上传 Logo' }}</small>
                </div>
                <input ref="logoInput" type="file" accept="image/png,image/svg+xml,image/jpeg" style="display:none" @change="onLogoPick" />
              </div>

              <!-- 右: 字段区 (2 列) -->
              <div class="brand-form">
                <el-form label-position="top">
                  <div class="brand-grid">
                    <el-form-item label="系统显示名称" required>
                      <el-input v-model="brandForm.systemName" placeholder="如: 精铸智造 ERP+MES" />
                    </el-form-item>
                    <el-form-item label="英文名称">
                      <el-input v-model="brandForm.enName" placeholder="JINGZHU INTELLIGENT MANUFACTURING" />
                    </el-form-item>
                    <el-form-item label="工厂名称" required>
                      <el-input v-model="brandForm.factory" placeholder="如: 精铸智造华东一厂" />
                    </el-form-item>
                    <el-form-item label="工厂编码">
                      <el-input v-model="brandForm.factoryCode" placeholder="JZ-HD-01" />
                    </el-form-item>
                    <el-form-item label="统一社会信用代码">
                      <el-input v-model="brandForm.creditCode" placeholder="9133XXXXXXXXXX8H" />
                    </el-form-item>
                    <el-form-item label="XK许可证编号">
                      <el-input v-model="brandForm.xkNo" placeholder="XK16-204-XXXX" />
                    </el-form-item>
                    <el-form-item label="工厂地址" class="brand-full">
                      <el-input v-model="brandForm.address" placeholder="浙江省杭州市临平区智造产业园18号" />
                    </el-form-item>
                    <el-form-item label="单据页脚" class="brand-full">
                      <el-input v-model="brandForm.footer" placeholder="精铸智造 · 系统生成 · 不可篡改" />
                    </el-form-item>
                  </div>
                </el-form>
              </div>
            </div>

            <div class="brand-note">
              <b>Logo 上传要求</b>
              <small>建议使用透明背景 PNG 或 SVG，尺寸不小于 256×256。修改后将同步更新登录页、导航栏和打印单据。</small>
            </div>

            <div class="brand-foot">
              <el-button @click="logoInput?.click()" link type="primary">更换 Logo</el-button>
              <el-button type="primary" @click="saveBrand">保存品牌设置</el-button>
            </div>
          </div>
        </div>
        <div v-else-if="activeTab === 'backup'">
          <div class="svc-banner">
            <span class="svc-icon"><el-icon :size="20"><Collection /></el-icon></span>
            <div class="svc-text"><b>数据备份</b><small>自动备份、手动导出与恢复保留策略</small></div>
            <span class="svc-tag ok">正常</span>
          </div>
          <Backup />
        </div>
        <div v-else-if="activeTab === 'log'">
          <div class="svc-banner">
            <span class="svc-icon"><el-icon :size="20"><Clock /></el-icon></span>
            <div class="svc-text"><b>操作日志</b><small>登录记录与关键操作审计留痕</small></div>
          </div>
          <OperationLog />
        </div>
        <div v-else-if="activeTab === 'feie'" class="sys-feie">
          <div class="svc-banner">
            <span class="svc-icon"><el-icon :size="20"><Printer /></el-icon></span>
            <div class="svc-text"><b>飞鹅云打印服务</b><small>当前已连接 2 台打印机，支持采购单、标签和生产单自动打印</small></div>
            <span class="svc-tag ok">服务正常</span>
          </div>
          <el-tabs v-model="feieTab">
            <el-tab-pane label="打印机配置" name="cfg"><FeiePrinterConfig /></el-tab-pane>
            <el-tab-pane label="打印模板" name="tpl"><FeiePrintTemplate /></el-tab-pane>
            <el-tab-pane label="打印日志" name="log"><FeiePrintLog /></el-tab-pane>
          </el-tabs>
        </div>
        <div v-else-if="activeTab === 'app'" class="sys-app">
          <div class="svc-banner">
            <span class="svc-icon"><el-icon :size="20"><Iphone /></el-icon></span>
            <div class="svc-text"><b>车间移动端 App</b><small>当前正式版本 V2.3.0 · Android · 发布于 2025-06-10</small></div>
            <span class="svc-tag ok">已发布</span>
          </div>
          <el-tabs v-model="appTab">
            <el-tab-pane label="版本管理" name="ver"><AppModules /></el-tab-pane>
            <el-tab-pane label="模板管理" name="tpl"><AppModules /></el-tab-pane>
          </el-tabs>
        </div>
        <div v-else-if="activeTab === 'ai'" class="sys-ai">
          <div class="svc-banner">
            <span class="svc-icon"><el-icon :size="20"><Search /></el-icon></span>
            <div class="svc-text"><b>经营 AI 助手</b><small>用于经营问答、库存分析、质量异常总结和报表辅助</small></div>
            <span class="svc-tag ok">已连接</span>
          </div>
          <Integration />
        </div>
        <div v-else class="sys-extras">
            <div class="sys-extras-card">
            <div class="sys-extras-card-head"><b>扩展设置</b><small>打印模板 / 工作台 / 飞鹅相关配置</small></div>
            <div class="sys-extras-grid">
              <div class="sys-extra-item" @click="router.push('/system/print-template')"><el-icon><Printer /></el-icon><div><strong>打印模板</strong><small>可视化设计单据打印</small></div><el-icon><ArrowRight /></el-icon></div>
              <div class="sys-extra-item" @click="activeTab = 'dashboard'"><el-icon><Odometer /></el-icon><div><strong>经营驾驶舱管理</strong><small>驾驶舱模块开关</small></div><el-icon><ArrowRight /></el-icon></div>
            </div>
          </div>
        </div>
        <!-- 经营驾驶舱管理 (v1.1.93: 驾驶舱模块开关, 复用 DashboardConfig 组件) -->
        <div v-if="activeTab === 'dashboard'" class="sys-dashboard">
          <div class="svc-banner">
            <span class="svc-icon"><el-icon :size="20"><Odometer /></el-icon></span>
            <div class="svc-text"><b>经营驾驶舱管理</b><small>驾驶舱 7 子模块的开关与显示顺序; 配置全局生效, 刷新工作台即时可见</small></div>
            <span class="svc-tag ok">受控</span>
          </div>
          <DashboardConfig />
        </div>
        <FeiePrintTemplate v-if="activeTab === 'feieTemplate'" />
        <FeiePrintLog v-if="activeTab === 'feieLog'" />
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, ArrowRight, Check, Printer, Odometer, Search,
  Tools, Collection, Clock, Iphone, Link,
  OfficeBuilding, Setting
} from '@element-plus/icons-vue'
import Settings from './Settings.vue'
import Backup from './Backup.vue'
import OperationLog from './OperationLog.vue'
import FeiePrinterConfig from './FeiePrinterConfig.vue'
import FeiePrintTemplate from './FeiePrintTemplate.vue'
import FeiePrintLog from './FeiePrintLog.vue'
import AppModules from './AppModules.vue'
import DashboardConfig from './DashboardConfig.vue'
import Integration from './Integration.vue'
import { useSystemName } from '@/composables/useSystemName'

const router = useRouter()
const activeTab = ref('config')

// ——— 内层 tab 状态 (飞鹅: 配置/模板/日志; App: 版本/模板) ———
const feieTab = ref('cfg')
const appTab = ref('ver')

// ——— 系统参数: 基础参数表单 + 系统信息只读卡 ———
/* global __APP_VERSION__, __BUILD_TIME__ */
import { onMounted } from 'vue'
import { configApi } from '@/api/system'
const cfg = reactive({
  systemName: '', lang: 'zh-CN', timezone: 'Asia/Shanghai',
  billPrefix: 'JZ', retention: 'forever', sessionTimeout: '30',
  audit: true, xkCheck: true
})
const backendInfo = ref({})
const serverTime = ref('')
const frontendVersion = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '—'
const frontendBuildTime = typeof __BUILD_TIME__ !== 'undefined' ? __BUILD_TIME__ : '—'
async function loadCfgInfo() {
  try { const r = await configApi.getByKey('SYSTEM_VERSION_INFO'); if (r?.data) backendInfo.value = JSON.parse(r.data) } catch {}
  serverTime.value = new Date().toLocaleString('zh-CN', { hour12: false })
}
async function saveConfig() {
  try {
    if (cfg.systemName.trim()) await saveSystemName(cfg.systemName.trim())
    ElMessage.success('基础参数已保存')
  } catch (e) { ElMessage.error(e.message || '保存失败') }
}

const { systemName, factoryName, saveSystemName, saveFactoryName, loadSystemName } = useSystemName()
onMounted(async () => {
  await loadCfgInfo()
  await loadSystemName()
  // 把已存的品牌/工厂名回填到 brand 表单
  brandForm.systemName = systemName.value
  brandForm.factory = factoryName.value
})

// Figma 主 7 分区 (左侧 nav 展示的项) + 隐藏扩展项
const tabs = [
  { key: 'config',   title: '系统参数',  sub: '版本与运行参数', icon: 'Tools' },
  { key: 'brand',    title: '品牌与工厂', sub: 'Logo、名称及工厂', icon: 'OfficeBuilding' },
  { key: 'backup',   title: '数据备份',  sub: '备份、恢复与保留', icon: 'Collection' },
  { key: 'log',      title: '操作日志',  sub: '登录与操作审计', icon: 'Clock' },
  { key: 'feie',     title: '飞鹅打印机', sub: '云打印设备配置', icon: 'Printer' },
  { key: 'app',      title: 'App 管理',  sub: '版本发布与更新', icon: 'Iphone' },
  { key: 'ai',       title: 'AI 对接',   sub: '模型与接口配置', icon: 'Link' },
  { key: 'dashboard', title: '经营驾驶舱管理', sub: '驾驶舱模块开关', icon: 'Odometer' }
]

// 扩展项 (通过"保存设置"占位或系统参数内入口触发, 不在主 nav 展示)
const brandForm = reactive({
  systemName: '', enName: '', factory: '', factoryCode: '',
  creditCode: '', xkNo: '', address: '', footer: ''
})
const logoInput = ref(null)
const logoPreview = ref('')
function triggerLogoUpload() { logoInput.value?.click() }
function onLogoPick(e) {
  const f = e.target.files?.[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = (ev) => { logoPreview.value = ev.target.result; brandForm.systemName = brandForm.systemName || systemName.value }
  reader.readAsDataURL(f)
  e.target.value = ''
}
function onLogoDrop(e) {
  const f = e.dataTransfer?.files?.[0]
  if (f) { const r = new FileReader(); r.onload = (ev) => (logoPreview.value = ev.target.result); r.readAsDataURL(f) }
}
async function saveBrand() {
  try {
    // 系统显示名称落到 SYS_NAME, 工厂名称落到 SYS_FACTORY (侧边栏/顶栏即时读取)
    if (brandForm.systemName.trim()) await saveSystemName(brandForm.systemName.trim())
    if (brandForm.factory.trim()) await saveFactoryName(brandForm.factory.trim())
    ElMessage.success('品牌设置已保存')
  } catch (e) { ElMessage.error(e.message || '保存失败') }
}
// 保存设置 = 触发当前分区对应子组件的默认保存 (各子组件自带保存按钮, 此处为统一入口占位)
function onSave() { ElMessage.info('请使用各分区内的「保存」按钮提交') }

// 隐藏扩展 tab (保持旧功能不丢)
function goPrint() { router.push('/system/print-template') }
</script>

<style scoped lang="scss">
/* 服务横幅 (Figma 各分区卡片头部: 图标 + 标题/描述 + 状态 tag) */
.svc-banner {
  display: flex; align-items: center; gap: 14px;
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 18px 20px; margin-bottom: 16px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.svc-icon {
  width: 44px; height: 44px; border-radius: 11px; flex: 0 0 auto;
  display: grid; place-items: center; background: #eef5ff; color: var(--primary-color);
}
.svc-text { flex: 1; min-width: 0; }
.svc-text b { display: block; font-size: 15px; font-weight: 730; color: var(--text); }
.svc-text small { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; }
.svc-tag {
  flex: 0 0 auto; padding: 5px 12px; border-radius: 20px;
  font-size: 12px; font-weight: 600;
  &.ok { background: #e7f7ee; color: #16a35f; }
  &.warn { background: #fff3e6; color: #d9822b; }
}
/* 内层 el-tabs 与横幅间距收紧 */
.sys-feie .el-tabs__header, .sys-app .el-tabs__header { margin-bottom: 12px; }

.sys-settings { position: relative; }

.sys-grid {
  display: grid; grid-template-columns: 280px 1fr; gap: 20px; align-items: start;
}
@media (max-width: 1050px) { .sys-grid { grid-template-columns: 1fr; } }

/* 左 nav */
.sys-nav { display: flex; flex-direction: column; gap: 8px; }
.sys-nav-item {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 16px; background: #fff; border: 1px solid var(--line);
  border-radius: 12px; cursor: pointer; transition: all .18s;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
  &:hover { border-color: #cfe0f2; box-shadow: 0 8px 24px rgba(20, 80, 150, .08); }
  &.active {
    border-color: var(--primary-color);
    box-shadow: 0 8px 24px rgba(22, 113, 217, .14);
    .sys-nav-icon { background: var(--primary-color); color: #fff; }
    .sys-nav-arrow { color: var(--primary-color); }
  }
}
.sys-nav-icon {
  width: 40px; height: 40px; border-radius: 10px; flex: 0 0 auto;
  display: grid; place-items: center; background: #eef5ff; color: var(--primary-color);
}
.sys-nav-text { flex: 1; min-width: 0; }
.sys-nav-text strong { display: block; font-size: 14px; color: var(--text); font-weight: 650; }
.sys-nav-text small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
.sys-nav-arrow { color: #b6c2d0; flex: 0 0 auto; }

/* 右内容 */
.sys-content { min-width: 0; }
.sys-brand-card, .sys-extras-card {
  background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 20px 22px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.sys-brand-card-head b, .sys-extras-card-head b { font-size: 15px; font-weight: 730; color: var(--text); }
.sys-brand-card-head small, .sys-extras-card-head small { display: block; font-size: 11px; color: var(--muted); margin: 4px 0 14px; }
/* 系统参数: 只读信息卡 + 基础参数表单 (Figma) */
.sys-config { display: flex; flex-direction: column; gap: 16px; }
.cfg-card { background: #fff; border: 1px solid var(--line); border-radius: 14px; padding: 20px 22px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04); }
.cfg-card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.cfg-card-head b { font-size: 15px; font-weight: 730; color: var(--text); }
.cfg-card-head small { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; }
.info-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
@media (max-width: 1100px) { .info-grid { grid-template-columns: repeat(2, 1fr); } }
.info-cell { background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 10px; padding: 12px 14px; }
.info-cell small { display: block; font-size: 11px; color: var(--muted); }
.info-cell b { display: block; font-size: 13px; font-weight: 650; color: var(--text); margin-top: 6px; }
.toggle-row {
  grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: 12px;
  background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 10px; padding: 12px 14px;
  div { min-width: 0; }
  b { display: block; font-size: 13px; font-weight: 650; color: var(--text); }
  small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
}
.cfg-foot { display: flex; justify-content: flex-end; margin-top: 14px; }

/* 品牌与工厂 (Figma) */
.sys-brand { display: flex; }
.brand-card {
  flex: 1; background: #fff; border: 1px solid var(--line); border-radius: 14px;
  padding: 20px 22px; box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.brand-card-head b { font-size: 16px; font-weight: 730; color: var(--text); }
.brand-card-head small { display: block; font-size: 11px; color: var(--muted); margin: 4px 0 16px; }
.brand-body { display: grid; grid-template-columns: 220px 1fr; gap: 24px; }
@media (max-width: 1100px) { .brand-body { grid-template-columns: 1fr; } }
.brand-logo-col { display: flex; flex-direction: column; }
.logo-drop {
  border: 1px dashed #c3d3e5; border-radius: 12px; background: #fbfcfe;
  padding: 20px; text-align: center; cursor: pointer; min-height: 120px;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
  &:hover { border-color: var(--primary-color); background: #eef5ff; }
  small { font-size: 11px; color: var(--muted); }
}
.logo-preview img { max-width: 96px; max-height: 96px; object-fit: contain; border-radius: 6px; }
.logo-empty { color: var(--primary-color); }
.brand-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }
.brand-grid .brand-full { grid-column: 1 / -1; }
@media (max-width: 700px) { .brand-grid { grid-template-columns: 1fr; } }
.brand-note {
  margin-top: 16px; background: #f6f9fd; border: 1px solid #eaf1f8; border-radius: 10px; padding: 12px 14px;
  b { font-size: 12px; color: var(--text); }
  small { display: block; font-size: 11px; color: var(--muted); margin-top: 4px; }
}
.brand-foot { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; }

.sys-extras { display: flex; }

.sys-extras-card { flex: 1; }
.sys-extras-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.sys-extra-item {
  display: flex; align-items: center; gap: 12px; padding: 14px 16px;
  background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 11px; cursor: pointer;
  color: var(--primary-color); transition: all .18s;
  &:hover { background: var(--primary-color); color: #fff; }
  div { flex: 1; min-width: 0; }
  strong { display: block; font-size: 13px; font-weight: 650; }
  small { display: block; font-size: 11px; opacity: .8; margin-top: 2px; }
}
</style>
