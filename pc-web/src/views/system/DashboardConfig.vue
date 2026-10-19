<template>
  <div>
    <!-- 说明 -->
    <div class="page-card" style="margin-bottom:16px">
      <div class="section-title">📊 经营驾驶舱子模块配置</div>
      <p class="muted-tip">
        关闭后, 对应模块不会在工作台渲染 (节省屏幕空间, 让关键信息更聚焦)。
        配置全局生效, 所有角色一致; 仅管理员可改。立即生效, 刷新工作台即可看到。
      </p>
    </div>

    <!-- 11 子模块勾选 (v1.1.94: 对齐驾驶舱 11 子模块, 含 4 总览子模块 kpiSales/trend/warning/ranking) -->
    <div class="page-card">
      <div class="toolbar">
        <span class="muted-tip" style="margin-right:12px">
          启用模块: {{ enabledCount }} / {{ sections.length }}
        </span>
        <el-button @click="onReload" :loading="loading">重新加载</el-button>
        <el-button type="primary" @click="onSave" :loading="submitting">保存</el-button>
        <el-button @click="onResetDefault" link>恢复默认</el-button>
      </div>

      <el-table :data="sections" border stripe v-loading="loading" row-key="key">
        <el-table-column label="模块" width="240">
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
        <el-table-column label="预览" width="160" align="center">
          <template #default="{ row }">
            <span class="preview-text muted-tip">{{ row.preview }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 排序 (简单上下) -->
    <div class="page-card" style="margin-top:16px">
      <div class="section-title">🔢 显示顺序</div>
      <p class="muted-tip">从上到下依次在工作台渲染, 拖动调整顺序 (启用状态下的项才参与排序)。</p>
      <div class="order-list">
        <div v-for="(s, idx) in orderedSections" :key="s.key" class="order-item" :class="{ disabled: !s.enabled }">
          <el-icon class="order-handle"><Rank /></el-icon>
          <span class="order-num">{{ idx + 1 }}</span>
          <el-icon class="order-icon" :style="{ color: s.color }"><component :is="s.icon" /></el-icon>
          <span class="order-name">{{ s.label }}</span>
          <el-tag v-if="!s.enabled" type="info" size="small">已停用</el-tag>
          <div class="order-actions">
            <el-button :disabled="idx === 0" link @click="onMove(idx, -1)">↑ 上移</el-button>
            <el-button :disabled="idx === orderedSections.length - 1" link @click="onMove(idx, 1)">↓ 下移</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 审计提示 -->
    <div class="page-card" style="margin-top:16px">
      <div class="section-title">📋 审计日志</div>
      <p class="muted-tip">
        每次保存会写一行 <code>sys_oper_log</code> (module=经营驾驶舱管理, businessType=UPDATE),
        可在「操作日志」tab 检索。
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Odometer, Cpu, Bell, Promotion, CircleCheckFilled, CircleCheck, Tools, Rank, TrendCharts, DataAnalysis } from '@element-plus/icons-vue'
import { configApi } from '@/api/system'

const CONFIG_KEY = 'DASHBOARD_CONFIG'

// 11 个子模块开关 (v1.1.94: 对齐 dashboard/Index.vue 的 sectionEnabled 键, 含 4 个总览子模块
// kpiSales/trend/warning/ranking; 顺序 = 显示顺序; 总览子模块在 经营驾驶舱管理 可独立开关/排序)
const defaultSections = [
  { key: 'compliance', label: '合规记录徽章', icon: 'CircleCheckFilled', color: '#1e6091', enabled: true,
    description: '合规模块顶部蓝条: 受控记录 · 禁止物理删除 · 审计留痕 · 数据留存≥2年 · 服务器时间戳 · 图片对象存储',
    preview: '合规模块顶部蓝条' },
  { key: 'kpiSales', label: '销售额 KPI', icon: 'Odometer', color: '#1e6091', enabled: true,
    description: '总览子模块: 今日销售/销售总额/今日采购/应收余额 4 卡 (ERP KPI, 需 dashboard:kpi 权限)',
    preview: '总览 · 4 KPI 卡' },
  { key: 'trend', label: '销售趋势 + 快捷功能', icon: 'TrendCharts', color: '#27ae60', enabled: true,
    description: '总览子模块: 近30天销售金额/毛利折线 + 6 个 ERP 快捷功能按钮 (需 dashboard:sales-trend 权限)',
    preview: '总览 · 折线 + 快捷' },
  { key: 'warning', label: '库存预警', icon: 'Bell', color: '#e67e22', enabled: true,
    description: '总览子模块: 低于安全库存的商品清单 (需 inventory:warning:list 权限)',
    preview: '总览 · 预警列表' },
  { key: 'ranking', label: '销售排行榜 TOP10', icon: 'DataAnalysis', color: '#c0392b', enabled: true,
    description: '总览子模块: 按客户金额排行 (需 dashboard:sales-ranking 权限)',
    preview: '总览 · 排行表' },
  { key: 'kpi', label: '资金 KPI', icon: 'Odometer', color: '#1e6091', enabled: true,
    description: '资金模块: XK 4 KPI (今日产量/设备运行率/待办维保/库存预警) 左色条 + 数值 + 副指标',
    preview: '资金模块 KPI 卡' },
  { key: 'productionBoard', label: '实时生产看板', icon: 'Cpu', color: '#27ae60', enabled: true,
    description: '生产模块: 吹膜线当前工单 + 进度条 + 状态徽章; 工序异常快速定位',
    preview: '生产模块 大卡' },
  { key: 'quality', label: '质量检查', icon: 'CircleCheck', color: '#27ae60', enabled: true,
    description: '质量模块: IQC 待审核批次 / 成品 OQC 合格率 / 生产环境检查 三级检验',
    preview: '质量模块 3 卡' },
  { key: 'maintenance', label: '设备维保', icon: 'Tools', color: '#e67e22', enabled: true,
    description: '维保模块: 待办维保 / 已逾期 / 设备运行率 3 KPI + 维保待办行',
    preview: '维保模块 3 KPI + 待办' },
  { key: 'todos', label: '待办事项', icon: 'Bell', color: '#e67e22', enabled: true,
    description: '待办模块: IQC 审核/设备异响/卫生整改/客诉追溯 等按优先级排序',
    preview: '待办模块 列表' },
  { key: 'quickLinks', label: '快捷入口', icon: 'Promotion', color: '#c0392b', enabled: true,
    description: '总览模块底部: 扫码入库/生产报工/维保反馈/安全巡检 4 块, 渐变色背景, 点击直达',
    preview: '总览模块底部色卡' }
]

const sections = ref(JSON.parse(JSON.stringify(defaultSections)))
const loading = ref(false)
const submitting = ref(false)

const enabledCount = computed(() => sections.value.filter(s => s.enabled).length)
const orderedSections = computed(() => sections.value.map((s, i) => ({ ...s, _order: i })))

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
        // 顺序
        if (Array.isArray(parsed.order)) {
          sections.value.sort((a, b) => {
            const ia = parsed.order.indexOf(a.key)
            const ib = parsed.order.indexOf(b.key)
            return (ia === -1 ? 999 : ia) - (ib === -1 ? 999 : ib)
          })
        }
      } catch (e) {
        console.warn('[DashboardConfig] parse fail:', e)
      }
    }
  } catch (e) {
    console.warn('[DashboardConfig] load fail:', e)
  } finally {
    loading.value = false
  }
}

function buildJson() {
  const out = { order: sections.value.map(s => s.key) }
  for (const s of sections.value) out[s.key] = { enabled: s.enabled }
  return JSON.stringify(out)
}

async function onSave() {
  try {
    await ElMessageBox.confirm(
      '保存后立即生效, 所有用户的工作台将按新配置渲染。是否继续?',
      '保存工作台配置',
      { confirmButtonText: '保存', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) { return }
  submitting.value = true
  try {
    await configApi.updateValue(CONFIG_KEY, buildJson())
    ElMessage.success('保存成功, 刷新工作台即可看到效果')
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    submitting.value = false
  }
}

function onReload() { loadData() }

function onResetDefault() {
  sections.value = JSON.parse(JSON.stringify(defaultSections))
  ElMessage.info('已恢复默认, 点击保存生效')
}

function onMove(idx, delta) {
  const j = idx + delta
  if (j < 0 || j >= sections.value.length) return
  const arr = sections.value
  ;[arr[idx], arr[j]] = [arr[j], arr[idx]]
}

onMounted(loadData)
</script>

<style scoped>
.module-cell { display: flex; align-items: center; gap: 10px; }
.module-icon { font-size: 22px; }
.module-name { font-size: 14px; font-weight: 500; color: #303133; }
.module-key { font-size: 11px; font-family: monospace; }
.preview-text { font-size: 12px; }

.order-list { display: flex; flex-direction: column; gap: 6px; }
.order-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; background: #fafbfc;
  border: 1px solid #e8e8e8; border-radius: 4px;
  &.disabled { opacity: 0.4; }
}
.order-handle { color: #bbb; font-size: 16px; }
.order-num {
  background: #1e6091; color: #fff; width: 22px; height: 22px;
  border-radius: 50%; display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600;
}
.order-icon { font-size: 18px; }
.order-name { font-size: 14px; color: #303133; flex: 1; }
.order-actions { font-size: 12px; }
</style>
