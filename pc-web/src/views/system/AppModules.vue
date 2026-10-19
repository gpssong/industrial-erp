<template>
  <div>
    <!-- 模块总览说明 -->
    <div class="page-card" style="margin-bottom:16px">
      <div class="section-title">📱 App 模块管理</div>
      <p class="muted-tip">
        关闭后, App 端所有角色都无法访问该模块 (含工作台快捷入口、详情页、tabBar),
        包括超管. 立即生效 (App 端下次启动/切前台自动拉取最新配置).
        子功能 override 可单独控制某个 perm 的开关, 默认跟随父模块.
      </p>
    </div>

    <!-- 8 业务模块表格 (含展开行展示子功能 override) -->
    <div class="page-card">
      <div class="toolbar">
        <span class="muted-tip" style="margin-right:12px">
          启用模块: {{ enabledCount }} / {{ modules.length }}
        </span>
        <el-button @click="onReload" :loading="loading">重新加载</el-button>
        <el-button type="primary" @click="onSave" :loading="submitting">保存</el-button>
      </div>

      <el-table :data="modules" border stripe v-loading="loading" row-key="key" :expand-row-keys="expandedKeys"
                @expand-change="onExpandChange">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding:8px 16px;background:#fafbfc">
              <div class="override-title">🔧 子功能 override (单独关闭某个 perm 的入口)</div>
              <el-checkbox-group v-model="row.overridePerms" @change="onOverrideChange(row)">
                <div v-for="perm in row.permList" :key="perm" class="override-row">
                  <el-checkbox :value="perm" :disabled="!row.enabled">
                    <span class="override-perm">{{ perm }}</span>
                    <span class="override-desc muted-tip">{{ permDesc(perm) }}</span>
                  </el-checkbox>
                </div>
              </el-checkbox-group>
              <div v-if="!row.permList.length" class="muted-tip" style="padding:8px">
                该模块当前无细粒度子功能可配置
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="label" label="模块" width="180" />
        <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="子功能" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.permList.length" size="small">{{ row.permList.length }} 项</el-tag>
            <span v-else class="muted-tip">-</span>
          </template>
        </el-table-column>
        <el-table-column label="当前状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
              {{ row.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="100" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="onToggle(row)" />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 审计提示 -->
    <div class="page-card" style="margin-top:16px">
      <div class="section-title">📋 审计日志</div>
      <p class="muted-tip">
        每次保存会写一行 <code>sys_oper_log</code> (module=App模块管理, businessType=UPDATE),
        可在「操作日志」tab 检索.
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { configApi } from '@/api/system'

/** 8 个业务模块固定配置 (与后端 AppModuleConfigDTO.DEFAULT_KEYS + MainLayout.vue 同步) */
const MODULE_DEFS = [
  { key: 'purchase',   label: '采购管理',  description: '扫码入库 / 采购入库单查询 / 采购订单',
    permList: ['purchase:receipt:list', 'purchase:receipt:query'] },
  { key: 'sales',      label: '销售管理',  description: '扫码出库 / 销售出库单查询',
    permList: ['sales:return:list', 'sales:delivery:list', 'sales:delivery:query'] },
  { key: 'inventory',  label: '库存管理',  description: '查库存 / 库存台账 / 库存预警 / 外勤盘点',
    permList: ['inventory:stock:list', 'inventory:ledger:list', 'inventory:check:list', 'inventory:warning:list'] },
  { key: 'production', label: '生产管理',  description: '生产加工单',
    permList: ['production:order:list'] },
  { key: 'base',       label: '基础资料',  description: '新增商品',
    permList: ['base:product:list'] },
  { key: 'finance',    label: '应收应付',  description: '暂未实现 App 端入口',
    permList: [] },
  { key: 'report',     label: '报表中心',  description: '经营简报 (KPI) / AI 助手',
    permList: ['report:view', 'dashboard:kpi', 'report:ai'] },
  { key: 'workflow',   label: '设备安全',  description: '仅 PC 端 (v1.1.79 决策)',
    permList: [] }
]

/** perm 字符串 → 中文描述 (App dashboard 真实存在过的入口) */
const PERM_DESCRIPTIONS = {
  'purchase:receipt:list':  '扫码入库 (主入口)',
  'purchase:receipt:query': '采购入库单查询',
  'sales:return:list':      '扫码出库 (主入口)',
  'sales:delivery:list':    '销售出库单列表入口',
  'sales:delivery:query':   '销售出库单查询',
  'inventory:stock:list':   '查库存 (工作台快捷)',
  'inventory:ledger:list':  '库存台账',
  'inventory:check:list':   '外勤盘点',
  'inventory:warning:list': '库存预警 (工作台区块)',
  'production:order:list':  '生产加工单',
  'base:product:list':      '新增商品',
  'report:view':            '经营简报入口',
  'dashboard:kpi':          '工作台 KPI 卡片',
  'report:ai':              'AI 助手'
}
function permDesc(perm) { return PERM_DESCRIPTIONS[perm] || '' }

const loading = ref(false)
const submitting = ref(false)
const expandedKeys = ref([])

/** 8 模块的 reactive state (顺序与 MODULE_DEFS 一致) */
const modules = reactive(MODULE_DEFS.map(d => ({
  key: d.key,
  label: d.label,
  description: d.description,
  permList: d.permList.slice(),
  enabled: true,
  overridePerms: []   // 显式关闭的 perm 列表 (enabled=false)
})))

const enabledCount = computed(() => modules.filter(m => m.enabled).length)

async function loadData() {
  loading.value = true
  try {
    const r = await configApi.getByKey('APP_MODULE_ENABLED')
    if (r?.data) {
      try {
        const parsed = JSON.parse(r.data)
        for (const m of modules) {
          const entry = parsed[m.key]
          if (entry) {
            m.enabled = entry.enabled !== false
            m.overridePerms = []
            const overrides = entry.overrides || {}
            for (const [perm, val] of Object.entries(overrides)) {
              if (val === false && m.permList.includes(perm)) m.overridePerms.push(perm)
            }
          }
        }
      } catch (e) {
        ElMessage.warning('配置 JSON 解析失败, 使用默认值: ' + e.message)
      }
    } else {
      ElMessage.info('尚未配置, 使用默认 (8 模块按代码硬编码: finance/workflow 默认关)')
      for (const m of modules) {
        m.enabled = m.key !== 'finance' && m.key !== 'workflow'
        m.overridePerms = []
      }
    }
  } catch (e) {
    // 拦截器已弹过 toast
    console.warn('[AppModules] 加载失败:', e)
  } finally {
    loading.value = false
  }
}

function onReload() { loadData() }

function onToggle(row) {
  if (!row.enabled && row.overridePerms.length) {
    ElMessage.info(`模块停用后, 子功能 override 自动失效 (${row.overridePerms.length} 项)`)
  }
  // 启用→禁用: 清掉 override (前端 UI 隐藏, 后端保存时无 override)
  if (!row.enabled) row.overridePerms = []
}

function onOverrideChange(row) {
  // override 仅当模块 enabled 时有效; 切换模块 enabled 时在 onToggle 已清
  // 这里不做事, 由保存时构建 payload
}

function onExpandChange(row, expanded) {
  const idx = expandedKeys.value.indexOf(row.key)
  if (expanded && idx < 0) expandedKeys.value.push(row.key)
  if (!expanded && idx >= 0) expandedKeys.value.splice(idx, 1)
}

async function onSave() {
  // 校验: 8 模块必须全在 (UI 限制无法删 key, 但保险起见)
  if (modules.length !== 8) {
    ElMessage.error('模块数量异常, 请联系管理员')
    return
  }
  await ElMessageBox.confirm(
    '保存后, App 端所有用户 (含超管) 立即按新配置显隐. 确定?',
    '确认保存',
    { type: 'warning' }
  ).catch(() => { throw new Error('cancel') })

  submitting.value = true
  try {
    const payload = { modules: {} }
    for (const m of modules) {
      const entry = { enabled: m.enabled, overrides: {} }
      if (m.enabled) {
        // 仅在 enabled=true 时保留 override (false 时全开)
        for (const p of m.permList) {
          entry.overrides[p] = !m.overridePerms.includes(p)
        }
      }
      payload.modules[m.key] = entry
    }
    const json = JSON.stringify(payload)
    await configApi.updateValue('APP_MODULE_ENABLED', json)
    ElMessage.success('保存成功, App 端下次启动/切前台生效')
  } catch (e) {
    if (e && e.message === 'cancel') return
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; align-items: center; }
.muted-tip { color: #909399; font-size: 12px; }
.section-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; color: #333; }
.page-card { background: #fff; padding: 16px; border-radius: 4px; box-shadow: 0 1px 4px rgba(0,21,41,0.04); }
code { background: #f0f2f5; padding: 2px 6px; border-radius: 3px; font-family: monospace; font-size: 12px; }

.override-title { font-size: 13px; font-weight: 600; color: #555; margin-bottom: 8px; }
.override-row { padding: 4px 0; }
.override-perm { font-family: monospace; font-size: 12px; color: #409eff; margin-right: 8px; }
.override-desc { font-size: 12px; margin-left: 4px; }
</style>
