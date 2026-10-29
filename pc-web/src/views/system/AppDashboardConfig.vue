<template>
  <div>
    <!-- 说明 -->
    <div class="page-card" style="margin-bottom:16px">
      <div class="section-title">📱 App 工作台区块配置</div>
      <p class="muted-tip">
        关闭后, App 端工作台上对应区块/功能分组不再渲染 (admin 全局一刀切, 所有 App 用户一致, 不联动权限).
        配置全局生效; App 端下次启动 / 切前台自动拉取最新配置 (5min 缓存), 立即生效无需重装 APK.
      </p>
    </div>

    <!-- 4 区块开关 (含 全部功能 7 子分组) -->
    <div class="page-card">
      <div class="toolbar">
        <span class="muted-tip" style="margin-right:12px">
          启用区块: {{ enabledCount }} / {{ sections.length }}
        </span>
        <el-button @click="onReload" :loading="loading">重新加载</el-button>
        <el-button type="primary" @click="onSave" :loading="submitting">保存</el-button>
        <el-button @click="onResetDefault" link>恢复默认</el-button>
      </div>

      <el-table :data="sections" border stripe v-loading="loading" row-key="key">
        <el-table-column label="区块" width="240">
          <template #default="{ row }">
            <div class="module-cell">
              <el-icon class="module-icon" :style="{ color: row.color }">
                <component :is="row.icon" />
              </el-icon>
              <div>
                <div class="module-name">{{ row.label }}</div>
                <div class="module-key muted-tip">{{ row.key }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="280" show-overflow-tooltip />
        <el-table-column label="当前状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
              {{ row.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="100" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" />
          </template>
        </el-table-column>
      </el-table>

      <!-- 全部功能 7 子分组开关 (仅在 全部功能 区块启用时可选) -->
      <div class="group-panel" :class="{ disabled: !allFunc.enabled }">
        <div class="group-title">
          🧩 全部功能 — 功能子分组开关
          <span class="muted-tip">(关闭的分组在工作台子标签中隐藏; 仅「全部功能」区块启用时可配)</span>
        </div>
        <el-checkbox-group v-model="allFunc.groups">
          <div class="group-list">
            <el-checkbox
              v-for="g in GROUP_DEFS"
              :key="g.key"
              :value="g.key"
              :disabled="!allFunc.enabled"
              class="group-item"
            >
              <span class="group-name">{{ g.label }}</span>
              <span class="group-desc muted-tip">{{ g.description }}</span>
            </el-checkbox>
          </div>
        </el-checkbox-group>
      </div>
    </div>

    <!-- 审计提示 -->
    <div class="page-card" style="margin-top:16px">
      <div class="section-title">📋 说明</div>
      <p class="muted-tip">
        配置存 <code>sys_config.APP_DASHBOARD_CONFIG</code>, 由 App 端
        <code>app/src/utils/appDashboardConfig.js</code> 读取 (key 不存在 / 拉取失败时默认全开).
        改动即时影响所有 App 用户的工作台渲染.
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Grid, Odometer, Bell, List } from '@element-plus/icons-vue'
import { configApi } from '@/api/system'

const CONFIG_KEY = 'APP_DASHBOARD_CONFIG'

// 全部功能 7 子分组 (与 App 端 dashboard FUNC_GROUP_ORDER + seed SQL 严格对齐, 顺序即渲染顺序)
const GROUP_DEFS = [
  { key: '生产',     label: '生产管理', description: '生产加工单 / 开工 / 报工 / 排程 / OEE / 米重采集 / 半成品·成品入库 / 首检·巡检' },
  { key: '采购',     label: '采购管理', description: '原料入库(扫码) / 采购入库单查询' },
  { key: '销售',     label: '销售管理', description: '扫码出库 / 销售出库单查询' },
  { key: '库存',     label: '库存管理', description: '查库存 / 库存台账 / 外勤盘点 / 库存预警' },
  { key: 'XK记录',   label: 'XK 记录',  description: 'XK 工作台 / 批次溯源 / 安全卫生 / 单据打印 / 审计归档' },
  { key: '现场填报', label: '现场填报', description: '设备维保(现场巡检+拍照取证)' },
  { key: '经营报表', label: '经营报表', description: '经营简报 / AI 助手' }
]

// 4 区块 (key 与 App 端 SECTION_KEYS + seed SQL 严格对齐; 全部功能 额外带 groups)
const defaultSections = [
  { key: 'allFunc',  label: '全部功能',   icon: 'Grid',     color: '#1e6091', enabled: true,
    description: '工作台「全部功能」卡 (含 7 个功能子分组横向标签); 关闭后整块隐藏' },
  { key: 'kpi',      label: 'KPI 四卡',   icon: 'Odometer', color: '#27ae60', enabled: true,
    description: '今日销售 / 累计销售 / 应收余额 / SKU 数 4 卡 (需 dashboard:kpi 权限, 叠加本开关)' },
  { key: 'warning',  label: '库存预警',   icon: 'Bell',     color: '#e67e22', enabled: true,
    description: '低于安全库存的商品清单 + 查看台账入口 (需 inventory:warning:list 权限, 叠加本开关)' },
  { key: 'todo',     label: '现场待办',   icon: 'List',     color: '#c0392b', enabled: true,
    description: '到货待检 IQC / 维保任务 / XK 复检 / 半成品·成品待入 列表 (各类受对应模块开关 + 本开关控制)' }
]

const DEFAULT_GROUPS = GROUP_DEFS.reduce((m, g) => { m[g.key] = true; return m }, {})

const sections = ref(defaultSections.map(s => ({ ...s, groups: s.key === 'allFunc' ? { ...DEFAULT_GROUPS } : undefined })))
const loading = ref(false)
const submitting = ref(false)

const allFunc = computed(() => sections.value.find(s => s.key === 'allFunc'))
const enabledCount = computed(() => sections.value.filter(s => s.enabled).length)

async function loadData() {
  loading.value = true
  try {
    const r = await configApi.getByKey(CONFIG_KEY)
    if (r?.data) {
      try {
        const parsed = JSON.parse(r.data)
        for (const s of sections.value) {
          if (parsed[s.key] && typeof parsed[s.key].enabled === 'boolean') {
            s.enabled = parsed[s.key].enabled
          }
        }
        // 恢复分组开关
        const pGroups = parsed.allFunc?.groups
        if (allFunc.value && pGroups && typeof pGroups === 'object') {
          for (const g of GROUP_DEFS) {
            if (typeof pGroups[g.key] === 'boolean') allFunc.value.groups[g.key] = pGroups[g.key]
          }
        }
      } catch (e) {
        console.warn('[AppDashboardConfig] parse fail:', e)
      }
    }
  } catch (e) {
    console.warn('[AppDashboardConfig] load fail:', e)
  } finally {
    loading.value = false
  }
}

function buildJson() {
  const out = {}
  for (const s of sections.value) {
    out[s.key] = { enabled: s.enabled !== false }
  }
  out.allFunc.groups = {}
  for (const g of GROUP_DEFS) {
    out.allFunc.groups[g.key] = allFunc.value?.groups?.[g.key] !== false
  }
  return JSON.stringify(out)
}

async function onSave() {
  try {
    await ElMessageBox.confirm(
      '保存后立即生效, 所有 App 用户的工作台将按新配置渲染。是否继续?',
      '保存 App 工作台配置',
      { confirmButtonText: '保存', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) { return }
  submitting.value = true
  try {
    await configApi.updateValue(CONFIG_KEY, buildJson())
    ElMessage.success('保存成功, App 端刷新工作台即可看到效果')
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    submitting.value = false
  }
}

function onReload() { loadData() }

function onResetDefault() {
  sections.value = defaultSections.map(s => ({ ...s, groups: s.key === 'allFunc' ? { ...DEFAULT_GROUPS } : undefined }))
  ElMessage.info('已恢复默认 (全开), 点击保存生效')
}

onMounted(loadData)
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; color: #333; }
.muted-tip { color: #909399; font-size: 12px; }
.toolbar { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin-bottom: 12px; }
.toolbar .muted-tip { font-size: 13px; margin-right: 8px; }

.module-cell { display: flex; align-items: center; gap: 10px; }
.module-icon { font-size: 22px; }
.module-name { font-size: 14px; font-weight: 500; color: #303133; }
.module-key { font-size: 11px; font-family: monospace; }

.group-panel { margin-top: 16px; padding: 14px 16px; background: #fafbfc; border: 1px solid #e8e8e8; border-radius: 6px; }
.group-panel.disabled { opacity: 0.55; }
.group-title { font-size: 13px; font-weight: 600; color: #303133; margin-bottom: 10px; }
.group-title .muted-tip { font-weight: 400; margin-left: 8px; }
.group-list { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px 16px; }
.group-item { display: flex; flex-direction: column; gap: 2px; height: auto; padding: 6px 8px; background: #fff; border: 1px solid #eee; border-radius: 6px; }
.group-name { font-size: 13px; color: #303133; font-weight: 500; }
.group-desc { font-size: 11px; }
</style>
