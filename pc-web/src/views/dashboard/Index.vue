<template>
  <div class="xk-dashboard">
    <!-- Figma 页面标题: eyebrow(日期·厂·班) + 大标题 + 右侧快捷动作 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">{{ eyebrowDate }} · 华东一厂 · 早班</div>
        <h1 class="xk-title">经营与生产驾驶舱</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="$router.push('/report/sales')"><el-icon><TrendCharts /></el-icon>经营日报</el-button>
        <el-button type="primary" @click="$router.push('/sales/delivery')"><el-icon><Plus /></el-icon>新建业务单</el-button>
      </div>
    </div>

    <!-- 模块切换栏 (按内容分模块, 对齐 Figma .stats-grid 前的模块 tab) -->
    <div class="dashboard-modules">
      <button
        v-for="m in dashModules"
        :key="m.key"
        class="dash-module-tab"
        :class="{ active: activeModule === m.key }"
        @click="activeModule = m.key"
      >
        <el-icon :size="16"><component :is="m.icon" /></el-icon>
        {{ m.title }}
      </button>
      <!-- 右上角快速设置浮窗 (替代隐藏 + perm 同步) -->
      <div class="section-toggles-bar">
        <el-popover placement="bottom-end" :width="320" trigger="click" popper-class="dashboard-toggles-popover">
          <template #reference>
            <el-button :icon="Setting" circle size="small" class="toggle-btn" title="自定义工作台显示" />
          </template>
          <div class="toggle-panel">
            <div class="toggle-title">📊 工作台显示设置</div>
            <div class="toggle-hint">勾选要展示的模块; 取消勾选即隐藏 (同步到系统设置 → 经营驾驶舱管理)</div>
            <div class="toggle-section">
              <div class="toggle-section-title">总览子模块 (随 经营驾驶舱管理 配置; 无权限项置灰)</div>
              <el-checkbox v-for="s in erpToggleSections" :key="s.key"
                :model-value="sectionEnabled[s.key]" :disabled="!s.permOk" class="toggle-item"
                @change="onErpToggle(s.key, $event)">
                <span :class="{ disabled: !s.permOk }">{{ s.label }}</span>
                <el-tag v-if="!s.permOk" type="info" size="small" effect="plain">无 perm</el-tag>
              </el-checkbox>
            </div>
            <el-divider style="margin: 8px 0" />
            <div class="toggle-section">
              <div class="toggle-section-title">驾驶舱子模块 (管理员配置)</div>
              <el-checkbox-group v-model="toggleSelection" @change="onToggleChange" class="toggle-list">
                <el-checkbox v-for="s in xkToggleSections" :key="s.key" :value="s.key" :true-value="s.key" class="toggle-item">
                  <el-icon class="toggle-icon" :style="{ color: s.color }"><component :is="s.icon" /></el-icon>
                  {{ s.label }}
                </el-checkbox>
              </el-checkbox-group>
            </div>
            <div class="toggle-footer">
              <el-button link size="small" @click="onToggleReset">恢复默认</el-button>
              <el-button link size="small" @click="goFullConfig">前往系统设置</el-button>
            </div>
          </div>
        </el-popover>
      </div>
    </div>

    <!-- 模块 0: 总览 (ERP 各子模块 + XK 快捷入口; 顺序由 DASHBOARD_CONFIG.order 驱动) -->
    <template v-if="activeModule === 'overview'">
      <template v-for="blk in orderedOverviewBlocks" :key="blk.key">
        <!-- 销售额 KPI (4 卡: 今日销售/总额/今日采购/应收) — ERP 销售额 KPI 模块 -->
        <el-row :gutter="12" v-if="blk.key === 'kpiSales' && kpiVisible && permGate('kpiSales')" style="margin-top:12px">
          <el-col :span="6" v-for="k in kpis" :key="k.label">
            <el-card class="kpi-card" :body-style="{ padding: '16px' }">
              <div class="kpi-inner" :style="{ borderLeft: `4px solid ${k.color}` }">
                <div class="kpi-label">{{ k.label }}</div>
                <div class="kpi-value">¥ {{ k.value }}</div>
                <div class="kpi-sub">{{ k.sub }}</div>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- 销售趋势 + 快捷功能 (一行左右) — ERP 趋势模块 -->
        <el-row :gutter="12" style="margin-top:12px" v-if="blk.key === 'trend' && permOk('trend') && permGate('trend')">
          <el-col :span="16">
            <el-card>
              <template #header><b>销售趋势 (近30天)</b></template>
              <v-chart :option="chartOption" autoresize style="height:320px" />
            </el-card>
          </el-col>
          <el-col :span="8">
            <el-card>
              <template #header><b>快捷功能</b></template>
              <div class="quick-grid">
                <el-button v-for="q in quickLinks" :key="q.path" type="primary" plain @click="$router.push(q.path)">
                  <el-icon><component :is="q.icon" /></el-icon>
                  {{ q.title }}
                </el-button>
              </div>
            </el-card>
          </el-col>
        </el-row>

        <!-- 库存预警 — ERP 库存预警模块 -->
        <el-row :gutter="12" style="margin-top:12px" v-if="blk.key === 'warning' && permOk('warning') && permGate('warning')">
          <el-col :span="24">
            <el-card>
              <template #header>
                <div style="display:flex;align-items:center;justify-content:space-between">
                  <b>库存预警</b>
                  <el-button size="small" type="primary" plain @click="goPurchaseSuggest">
                    生成采购建议 <el-icon><ArrowRight /></el-icon>
                  </el-button>
                </div>
              </template>
              <el-table :data="warningList" size="small" max-height="240">
                <el-table-column prop="productCode" label="编码" width="90" />
                <el-table-column prop="productName" label="商品" />
                <el-table-column prop="warehouseName" label="仓库" width="80" />
                <el-table-column prop="qty" label="当前库存" width="80" align="right">
                  <template #default="{ row }">
                    <span style="color:#c0392b;font-weight:bold">{{ row.qty }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="safetyStock" label="安全库存" width="80" align="right" />
              </el-table>
            </el-card>
          </el-col>
        </el-row>

        <!-- 销售排行榜 TOP10 — ERP 排行模块 -->
        <el-row :gutter="12" style="margin-top:12px" v-if="blk.key === 'ranking' && permOk('ranking') && permGate('ranking')">
          <el-col :span="24">
            <el-card>
              <template #header><b>销售排行榜 TOP10</b></template>
              <el-table :data="ranking" size="small" max-height="240">
                <el-table-column type="index" label="#" width="50" />
                <el-table-column prop="customerName" label="客户" />
                <el-table-column prop="totalAmount" label="金额" width="120" align="right">
                  <template #default="{ row }">¥ {{ row.totalAmount }}</template>
                </el-table-column>
              </el-table>
            </el-card>
          </el-col>
        </el-row>

        <!-- XK 快捷入口 (4 块色卡) — XK 快捷入口模块 -->
        <template v-if="blk.key === 'quickLinks'">
          <el-row :gutter="12" class="quick-row">
            <el-col :span="6" v-for="q in xkQuickLinks" :key="q.path">
              <el-card class="quick-card" shadow="hover" @click="$router.push(q.path)">
                <div class="quick-inner" :style="{ background: q.bg }">
                  <el-icon class="quick-icon"><component :is="q.icon" /></el-icon>
                  <div>
                    <div class="quick-title">{{ q.title }}</div>
                    <div class="quick-sub">{{ q.sub }}</div>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </template>
      </template>
    </template>

    <!-- 模块 1: 生产 -->
    <template v-if="activeModule === 'production' && sectionEnabled.productionBoard">
      <el-card>
        <template #header>
          <div class="card-header">
            <b>📊 实时生产看板</b>
            <span class="sub">车间产线运行状态</span>
          </div>
        </template>
        <div v-for="line in xkKpi.productionLines" :key="line.id" class="line-item">
          <div class="line-row">
            <el-icon class="line-icon"><Cpu /></el-icon>
            <div class="line-name">{{ line.name }}</div>
            <div class="line-product">{{ line.product }} · {{ line.workOrder }}</div>
            <div class="line-progress-text">{{ line.progress }}%</div>
          </div>
          <el-progress
            :percentage="line.progress"
            :stroke-width="10"
            :show-text="false"
            :color="line.progress >= 70 ? '#27ae60' : line.progress >= 40 ? '#1e6091' : '#e67e22'"
          />
          <el-tag class="line-status" :type="line.status === 'RUNNING' ? 'success' : 'info'" size="small">运行中</el-tag>
        </div>
      </el-card>

      <!-- v1.1.119: 吹膜米克重实时 (ESP32 采集 → 真数据, 无数据则提示) -->
      <el-card v-if="weightDevices.length" style="margin-top:12px">
        <template #header>
          <div class="card-header">
            <b><el-icon style="vertical-align:-2px"><Odometer /></el-icon> 吹膜米克重实时</b>
            <span class="sub">ESP32 采集 · 冠尚达吹膜机</span>
            <el-button link type="primary" style="margin-left:auto" @click="$router.push('/production/weight')">
              查看曲线 <el-icon><ArrowRight /></el-icon>
            </el-button>
          </div>
        </template>
        <div v-for="wd in weightDevices" :key="wd.id" class="weight-cell">
          <div class="weight-cell-head">
            <el-tag :type="wd.onlineStatus === 'ONLINE' ? 'success' : 'info'" size="small" effect="plain">
              {{ wd.onlineStatus === 'ONLINE' ? '在线' : '离线' }}
            </el-tag>
            <b>{{ wd.deviceName || wd.deviceCode }}</b>
            <span class="weight-dev-code">{{ wd.deviceCode }}</span>
          </div>
          <div class="weight-body">
            <div class="weight-kpis">
              <div class="wk wk-gauge" :class="{ over: wd.lastOver }">
                <div class="wk-num">{{ wd.lastWeight ?? '—' }}<small>g</small></div>
                <div class="wk-label">实际克重</div>
                <div class="wk-sub">设定 {{ wd.setWeight ?? '—' }} · 偏差 {{ wd.lastDeviation ?? '—' }} {{ wd.lastOver ? '· 超差' : '' }}</div>
              </div>
              <div class="wk">
                <div class="wk-num">{{ wd.lastSpeed ?? '—' }}<small>mm/s</small></div>
                <div class="wk-label">计米速度</div>
              </div>
              <div class="wk">
                <div class="wk-num">{{ wd.lastRollLength ?? '—' }}<small>m</small></div>
                <div class="wk-label">计米长度</div>
              </div>
              <div class="wk">
                <div class="wk-num">{{ wd.lastOutputKg ?? '—' }}<small>kg</small></div>
                <div class="wk-label">计米重量</div>
              </div>
              <div class="wk">
                <div class="wk-num">{{ wd.lastMachineYield ?? '—' }}<small>kg/l</small></div>
                <div class="wk-label">机器产量</div>
              </div>
              <div class="wk">
                <div class="wk-num">{{ wd.lastCurrentWeight ?? '—' }}<small>kg</small></div>
                <div class="wk-label">当前重量</div>
              </div>
              <div class="wk">
                <div class="wk-num">{{ wd.lastUpperHz ?? '—' }}<small>Hz</small></div>
                <div class="wk-label">上牵引</div>
              </div>
              <div class="wk">
                <div class="wk-num">{{ wd.lastGaugeFix ?? '—' }}</div>
                <div class="wk-label">克重修正</div>
              </div>
            </div>
            <div v-if="wd.spark.length" class="weight-spark">
              <v-chart :option="sparkOpt(wd)" :autoresize="true" style="height:96px;width:220px" />
              <div class="weight-spark-cap">实际克重趋势 (近 {{ wd.spark.length }} 点)</div>
            </div>
            <div v-else class="weight-empty">暂无采样 — ESP32 上报后自动出现</div>
          </div>
        </div>
      </el-card>
    </template>

    <!-- 模块 2: 质量 -->
    <template v-if="activeModule === 'quality' && sectionEnabled.quality">
      <el-card>
        <template #header><div class="card-header"><b>🛡️ 质量与 XK 检查</b><span class="sub">IQC / 成品 / 环境 三级检验</span></div></template>
        <div class="quality-grid">
          <div class="quality-cell qc-blue">
            <div class="qc-head"><span class="qc-title">IQC 原料进货检验</span><el-tag type="primary" size="small" effect="plain">受控</el-tag></div>
            <div class="qc-num">{{ xkKpi.todos.filter(t => t.bizType === 'INSPECT').length || '—' }}<small>待审核批次</small></div>
            <el-button link type="primary" @click="$router.push('/report/quality')">查看质量报表 <el-icon><ArrowRight /></el-icon></el-button>
          </div>
          <div class="quality-cell qc-green">
            <div class="qc-head"><span class="qc-title">成品 OQC 检验</span><el-tag type="success" size="small" effect="plain">合格放行</el-tag></div>
            <div class="qc-num">100%<small>合格率</small></div>
            <el-button link type="primary" @click="$router.push('/xk/trace')">批次追溯 <el-icon><ArrowRight /></el-icon></el-button>
          </div>
          <div class="quality-cell qc-amber">
            <div class="qc-head"><span class="qc-title">生产环境检查</span><el-tag type="warning" size="small" effect="plain">整改中</el-tag></div>
            <div class="qc-num">1<small>不符合项</small></div>
            <el-button link type="primary" @click="$router.push('/xk/safety')">安全卫生巡检 <el-icon><ArrowRight /></el-icon></el-button>
          </div>
        </div>
      </el-card>
    </template>

    <!-- 模块 3: 维保 -->
    <template v-if="activeModule === 'maintenance' && sectionEnabled.maintenance">
      <el-card>
        <template #header><div class="card-header"><b>🔧 设备维保</b><span class="sub">预维护计划 · 待办反馈</span></div></template>
        <div class="maintain-grid">
          <div class="maintain-kpi mk-blue"><div class="mk-num">{{ xkKpi.pendingMaintain.value }}</div><div class="mk-label">待办维保</div></div>
          <div class="maintain-kpi mk-red"><div class="mk-num">{{ xkKpi.pendingMaintain.overdue }}</div><div class="mk-label">已逾期</div></div>
          <div class="maintain-kpi mk-green"><div class="mk-num">{{ xkKpi.equipmentRun.value || '—' }}%</div><div class="mk-label">设备运行率</div></div>
        </div>
        <div v-for="t in xkKpi.todos.filter(x => x.bizType === 'MAINTAIN')" :key="t.id" class="maintain-row">
          <el-tag :type="t.level === 'URGENT' ? 'danger' : t.level === 'HIGH' ? 'warning' : 'info'" size="small" effect="dark">{{ levelLabel(t.level) }}</el-tag>
          <div class="maintain-title">{{ t.subtitle }}</div>
          <div class="maintain-time">{{ t.time }}</div>
          <el-button link type="primary" @click="$router.push('/xk/maintenance')">处理 <el-icon><ArrowRight /></el-icon></el-button>
        </div>
      </el-card>
    </template>

    <!-- 模块 4: 待办 -->
    <template v-if="activeModule === 'todos' && sectionEnabled.todos">
      <el-card>
        <template #header><div class="card-header"><b>🔔 待办事项</b><span class="sub">按优先级排序</span></div></template>
        <div class="todo-list">
          <div v-for="t in xkKpi.todos" :key="t.id" class="todo-item">
            <el-tag
              :type="t.level === 'URGENT' ? 'danger' : t.level === 'HIGH' ? 'warning' : 'info'"
              size="small" effect="dark" class="todo-level"
            >{{ levelLabel(t.level) }}</el-tag>
            <div class="todo-content">
              <div class="todo-title">{{ t.title }}</div>
              <div class="todo-sub">{{ t.subtitle }}</div>
            </div>
            <div class="todo-time">{{ t.time }}</div>
          </div>
        </div>
      </el-card>
    </template>

    <!-- 模块 5: 合规 -->
    <template v-if="activeModule === 'compliance' && sectionEnabled.compliance">
      <div class="compliance-strip">
        <div class="left">
          <el-icon class="shield"><CircleCheckFilled /></el-icon>
          <div>
            <div class="title">XK 合规记录 · 受控记录</div>
            <div class="sub">受控记录 · 禁止物理删除 · 审计留痕 · 数据留存≥2年 · 服务器时间戳 · 图片对象存储</div>
          </div>
        </div>
        <el-tag type="info" effect="plain" size="small">受控记录</el-tag>
      </div>
      <el-card>
        <template #header><div class="card-header"><b>合规审计入口</b><span class="sub">归档查询 · 审计日志</span></div></template>
        <div class="compliance-entries">
          <div class="compliance-entry" @click="$router.push('/xk/audit')">
            <el-icon :size="22"><Clock /></el-icon>
            <div><strong>审计日志与记录归档</strong><small>系统自动写入不可编辑 · 保存期限≥2年</small></div>
            <el-icon><ArrowRight /></el-icon>
          </div>
          <div class="compliance-entry" @click="$router.push('/xk/print')">
            <el-icon :size="22"><Printer /></el-icon>
            <div><strong>单据与打印</strong><small>PDF 校验值 · 只读存档 · 操作留痕</small></div>
            <el-icon><ArrowRight /></el-icon>
          </div>
          <div class="compliance-entry" @click="$router.push('/xk/trace')">
            <el-icon :size="22"><Promotion /></el-icon>
            <div><strong>批次全链路追溯</strong><small>原料批 → 半成品 → 成品 双向追溯</small></div>
            <el-icon><ArrowRight /></el-icon>
          </div>
        </div>
      </el-card>
    </template>

    <!-- 模块 6: 资金 (XK 4 KPI 卡; ERP 销售 KPI 已作为「销售额 KPI」总览子模块可独立配置/排序) -->
    <template v-if="activeModule === 'funds'">
      <el-row :gutter="12" v-if="sectionEnabled.kpi" class="xk-kpi-row">
        <el-col :span="6">
          <el-card class="kpi-card" :body-style="{ padding: '16px' }" shadow="hover">
            <div class="kpi-inner" style="border-left: 4px solid #1e6091">
              <div class="kpi-label">今日产量</div>
              <div class="kpi-value">{{ xkKpi.todayOutput.value }}<span class="unit"> {{ xkKpi.todayOutput.unit }}</span></div>
              <div class="kpi-sub up">▲ {{ xkKpi.todayOutput.delta }} 较昨日</div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="kpi-card" :body-style="{ padding: '16px' }" shadow="hover">
            <div class="kpi-inner" style="border-left: 4px solid #27ae60">
              <div class="kpi-label">设备运行率</div>
              <div class="kpi-value">{{ xkKpi.equipmentRun.value }}<span class="unit"> %</span></div>
              <div class="kpi-sub">{{ xkKpi.equipmentRun.running }} / {{ xkKpi.equipmentRun.total }} 台</div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="kpi-card" :body-style="{ padding: '16px' }" shadow="hover">
            <div class="kpi-inner" style="border-left: 4px solid #e67e22">
              <div class="kpi-label">待办维保</div>
              <div class="kpi-value">{{ xkKpi.pendingMaintain.value }}<span class="unit"> {{ xkKpi.pendingMaintain.unit }}</span></div>
              <div class="kpi-sub down">● {{ xkKpi.pendingMaintain.overdue }} 项已逾期</div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card class="kpi-card" :body-style="{ padding: '16px' }" shadow="hover">
            <div class="kpi-inner" style="border-left: 4px solid #c0392b">
              <div class="kpi-label">库存预警</div>
              <div class="xk-kpi-value">{{ xkKpi.stockWarning.value }}<span class="unit"> {{ xkKpi.stockWarning.unit }}</span></div>
              <div class="kpi-sub">⚠ {{ xkKpi.stockWarning.note }}</div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </template>

  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { reportApi } from '@/api/report'
import { stockApi } from '@/api/inventory'
import { configApi } from '@/api/system'
import { xkMock } from '@/api/xk'
import { weightApi } from '@/api/production'
import {
  Cpu, CircleCheckFilled, CircleCheck, Camera, SetUp, Tools, Document,
  Setting, Plus, TrendCharts, Grid, Money, Printer, Clock, Promotion, Bell,
  ArrowRight, DataAnalysis, Odometer
} from '@element-plus/icons-vue'
import dayjs from 'dayjs'

// Figma 页面标题 eyebrow: 当前日期
const eyebrowDate = computed(() => {
  const d = dayjs()
  return `${d.format('YYYY')}年${d.format('M')}月${d.format('D')}日`
})
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, GridComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, GridComponent, LegendComponent])

const router = useRouter()
const userStore = useUserStore()

// =====================================================================
// v1.1.53 ERP 原有 4 section perm 门禁 (KPI/趋势/排行/库存预警 独立可选)
// =====================================================================
const kpiVisible     = computed(() => userStore.hasPerm('dashboard:kpi'))
const trendVisible   = computed(() => userStore.hasPerm('dashboard:sales-trend'))
const rankingVisible = computed(() => userStore.hasPerm('dashboard:sales-ranking'))
const warningVisible = computed(() => userStore.hasPerm('inventory:warning:list'))

// ERP 总览子模块 perm 门禁 (每个模块独立可选, 无 perm 则置灰)
// v1.1.94: 显示状态 (启用/停用 + 顺序) 由 admin 配置 sectionEnabled (经营驾驶舱管理) 统一驱动,
// 不再用本地 erpVisible — 与 系统设置 → 经营驾驶舱管理 单一数据源
function permOk(key) {
  const ok = {
    kpi: kpiVisible.value,
    trend: trendVisible.value,
    warning: warningVisible.value,
    ranking: rankingVisible.value
  }
  return ok[key] !== false
}
function permGate(key) { return permOk(key) } // 无权限时总览子模块整体不渲染
const erpToggleSections = [
  { key: 'kpi',     label: '销售额 KPI (今日销售/总额/采购/应收)', permOk: computed(() => kpiVisible.value) },
  { key: 'trend',   label: '销售趋势 + 快捷功能',                 permOk: computed(() => trendVisible.value) },
  { key: 'warning', label: '库存预警',                            permOk: computed(() => warningVisible.value) },
  { key: 'ranking', label: '销售排行榜 TOP10',                    permOk: computed(() => rankingVisible.value) }
]
function onErpToggle(key, val) {
  sectionEnabled.value[key] = !!val
  if (val && !toggleSelection.value.includes(key)) toggleSelection.value.push(key)
  else if (!val) toggleSelection.value = toggleSelection.value.filter(k => k !== key)
  persistConfig()
}

// =====================================================================
// 驾驶舱按内容分模块 (v1.1.93: 模块 tab 切换 + 子模块 admin 可配)
// =====================================================================
// 7 个子模块的 admin 可配开关 (存 sys_config.DASHBOARD_CONFIG, 兼容旧 6 键)
const DEFAULT_DASHBOARD_CONFIG = {
  compliance:    { enabled: true },
  kpi:           { enabled: true },
  productionBoard: { enabled: true },
  quality:       { enabled: true },
  maintenance:   { enabled: true },
  todos:         { enabled: true },
  quickLinks:    { enabled: true },
  kpiSales:      { enabled: true },
  trend:         { enabled: true },
  warning:       { enabled: true },
  ranking:       { enabled: true },
  order: ['compliance', 'kpiSales', 'trend', 'warning', 'ranking', 'quickLinks',
    'kpi', 'productionBoard', 'quality', 'maintenance', 'todos']
}
const sectionEnabled = ref({
  compliance: true, kpi: true, productionBoard: true, quality: true, maintenance: true,
  todos: true, quickLinks: true,
  // v1.1.94 新增 4 个总览子模块 (ERP 趋势/预警/排行/销售额KPI)
  kpiSales: true, trend: true, warning: true, ranking: true
})
// admin 浮窗「驾驶舱子模块」勾选列表 (对应 sectionEnabled 键; 顺序 = DASHBOARD_CONFIG.order)
const xkToggleSections = [
  { key: 'compliance',       label: '合规记录徽章',   icon: 'CircleCheckFilled', color: '#1e6091' },
  { key: 'kpiSales',         label: '销售额 KPI',     icon: 'Odometer',          color: '#1e6091' },
  { key: 'trend',            label: '销售趋势 + 快捷功能', icon: 'TrendCharts',   color: '#27ae60' },
  { key: 'warning',          label: '库存预警',       icon: 'Bell',              color: '#e67e22' },
  { key: 'ranking',          label: '销售排行榜 TOP10', icon: 'DataAnalysis',    color: '#c0392b' },
  { key: 'quickLinks',       label: '快捷入口',       icon: 'Promotion',         color: '#c0392b' },
  { key: 'kpi',              label: '资金 KPI',       icon: 'Odometer',          color: '#1e6091' },
  { key: 'productionBoard',  label: '实时生产看板',   icon: 'Cpu',               color: '#27ae60' },
  { key: 'quality',          label: '质量检查',       icon: 'CircleCheck',       color: '#27ae60' },
  { key: 'maintenance',      label: '设备维保',       icon: 'Tools',             color: '#e67e22' },
  { key: 'todos',            label: '待办事项',       icon: 'Bell',              color: '#e67e22' }
]

// v1.1.94: 总览 tab 内的子模块 (ERP 趋势/预警/排行/销售额KPI + XK 快捷入口), 顺序由 order 驱动
const OVERVIEW_BLOCK_KEYS = ['kpiSales', 'trend', 'warning', 'ranking', 'quickLinks']
// 全局模块顺序 (存 DASHBOARD_CONFIG.order; 总览子模块按此排, 非总览键排后)
const dashOrder = ref(xkToggleSections.map(s => s.key))
const overviewOrder = computed(() =>
  dashOrder.value.filter(k => OVERVIEW_BLOCK_KEYS.includes(k))
)
const orderedOverviewBlocks = computed(() =>
  overviewOrder.value.map(k => xkToggleSections.find(s => s.key === k)).filter(Boolean)
    .filter(s => sectionEnabled.value[s.key])
)
const toggleSelection = ref(xkToggleSections.filter(s => sectionEnabled.value[s.key]).map(s => s.key))

// 模块 tab (按内容分 7 模块; overview=综合总览, 其余各模块独立)
const activeModule = ref('overview')
const dashModules = [
  { key: 'overview',     title: '总览',   icon: 'Grid' },
  { key: 'production',   title: '生产',   icon: 'Cpu' },
  { key: 'quality',      title: '质量',   icon: 'CircleCheck' },
  { key: 'maintenance',  title: '维保',   icon: 'Tools' },
  { key: 'todos',        title: '待办',   icon: 'Bell' },
  { key: 'compliance',   title: '合规',   icon: 'CircleCheckFilled' },
  { key: 'funds',        title: '资金',   icon: 'Money' }
]
// tab 可见性: 各子模块停用后, 若该 tab 下已无任何启用模块则隐藏 (总览恒显)
const visibleDashModules = computed(() => dashModules.filter(m => {
  if (m.key === 'overview') return overviewHasContent.value
  if (m.key === 'funds') return sectionEnabled.value.kpi
  return sectionEnabled.value[m.key]
}))
// 总览 tab 是否还有可渲染内容 (任一总览子模块启用且有权限)
const overviewHasContent = computed(() =>
  OVERVIEW_BLOCK_KEYS.some(k => sectionEnabled.value[k] && permGate(k))
)
// 当前 tab 被隐藏时 (全部停用) 回落到总览
watch([visibleDashModules], () => {
  if (!visibleDashModules.value.find(m => m.key === activeModule.value)) activeModule.value = 'overview'
}, { immediate: true })

// =====================================================================
// 数据
// =====================================================================
const kpis = ref([
  { label: '今日销售', value: '0.00', sub: '元', color: '#1e6091' },
  { label: '销售总额', value: '0.00', sub: '元', color: '#27ae60' },
  { label: '今日采购', value: '0.00', sub: '元', color: '#e67e22' },
  { label: '应收余额', value: '0.00', sub: '元', color: '#c0392b' }
])
const warningList = ref([])
const ranking = ref([])
const trend = ref([])

const xkKpi = ref({
  todayOutput: { value: 0, unit: '吨', delta: '+0%' },
  equipmentRun: { value: 0, running: 0, total: 0 },
  pendingMaintain: { value: 0, overdue: 0, unit: '项' },
  stockWarning: { value: 0, unit: '种', note: '' },
  productionLines: [],
  todos: []
})

// ERP 原有快捷功能 (v1.1.11 沿用)
const quickLinks = [
  { path: '/sales/delivery', title: '销售出库', icon: 'TakeawayBox' },
  { path: '/purchase/receipt', title: '采购入库', icon: 'Box' },
  { path: '/production/order', title: '生产加工单', icon: 'SetUp' },
  { path: '/inventory/stock', title: '库存查询', icon: 'Grid' },
  { path: '/finance/arap', title: '应收应付', icon: 'Money' },
  { path: '/report/sales', title: '销售报表', icon: 'TrendCharts' }
]

// =====================================================================
// v1.1.119: 吹膜米克重实时 (ESP32 采集 → 真数据)
// =====================================================================
const weightDevices = ref([])   // 每个采集器: { ...device, lastWeight, lastSpeed, lastDeviation, lastOver, setWeight, spark:[] }
const weightTimer = ref(null)

/** 拉某采集器最近 N 条曲线 + 计算最新值/超差标记 */
async function loadWeightDevice(d, limit) {
  const r = await weightApi.curve({ deviceId: d.id, limit })
  const rows = (r && (r.data || r)) || []
  const first = rows[0] || {}
  const num = v => v != null ? Number(v) : null
  d.spark = rows.map(x => num(x.actualWeight)).filter(v => v != null && !isNaN(v))
  d.lastWeight     = num(first.actualWeight)
  d.lastSpeed      = num(first.lineSpeed)
  d.lastDeviation  = num(first.deviation)
  d.lastOver       = Number(first.isOver) === 1
  d.setWeight      = num(first.setWeight)
  // 全字段 (v1.1.119b MCGS 屏 12 量)
  d.lastRollLength     = num(first.rollLength)
  d.lastOutputKg       = num(first.outputKg)
  d.lastMachineYield   = num(first.machineYield)
  d.lastCurrentWeight  = num(first.currentWeight)
  d.lastUpperHz        = num(first.upperHz)
  d.lastGaugeFix       = num(first.gaugeFix)
}

async function loadWeightData() {
  try {
    const r = await weightApi.devicePage({ pageNum: 1, pageSize: 50 })
    const devices = (r && (r.data && r.data.records) || r.data || []) || []
    weightDevices.value = devices
    for (const d of devices) {
      try { await loadWeightDevice(d, 40) } catch (e) { /* 无数据静默 */ }
    }
  } catch (e) { /* 401/500 静默, 卡片不渲染 (v-if weightDevices.length) */ }
}

function startWeightRefresh() {
  stopWeightRefresh()
  weightTimer.value = setInterval(loadWeightData, 30000)   // 30s 刷新
}
function stopWeightRefresh() {
  if (weightTimer.value) { clearInterval(weightTimer.value); weightTimer.value = null }
}

/** 迷你曲线 option (隐藏轴, 只留折线 + 最新点) */
function sparkOpt(wd) {
  const over = wd.lastOver
  return {
    grid: { left: 2, right: 2, top: 4, bottom: 2 },
    xAxis: { type: 'category', show: false, data: wd.spark.map((_, i) => i) },
    yAxis: { type: 'value', show: false, min: 'dataMin', max: 'dataMax' },
    tooltip: { show: false },
    series: [{
      type: 'line', data: wd.spark, smooth: true, symbol: 'none',
      lineStyle: { width: 2, color: over ? '#f56c6c' : '#1e6091' },
      areaStyle: { color: over ? 'rgba(245,108,108,.12)' : 'rgba(30,96,145,.12)' }
    }]
  }
}

// XK 新增快捷入口
const xkQuickLinks = [
  { path: '/xk/material', title: '扫码入库', sub: '原料批次 + 标签照片', icon: 'Camera',  bg: 'linear-gradient(135deg, #e3f2fd 0%, #bbdefb 100%)' },
  { path: '/production/order', title: '生产报工', sub: '工单进度 · 工序报工', icon: 'SetUp',   bg: 'linear-gradient(135deg, #e8f5e9 0%, #c8e6c9 100%)' },
  { path: '/xk/maintenance', title: '维保反馈', sub: '现场照片 · 处理记录', icon: 'Tools',  bg: 'linear-gradient(135deg, #fff3e0 0%, #ffe0b2 100%)' },
  { path: '/xk/safety', title: '安全巡检', sub: '整改前/后 闭环', icon: 'Document', bg: 'linear-gradient(135deg, #fce4ec 0%, #f8bbd0 100%)' }
]

const chartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['销售金额', '销售毛利'] },
  grid: { top: 30, left: 50, right: 30, bottom: 30 },
  xAxis: { type: 'category', data: trend.value.map(t => t.date) },
  yAxis: { type: 'value' },
  series: [
    { name: '销售金额', type: 'line', smooth: true, data: trend.value.map(t => t.amount), itemStyle: { color: '#1e6091' } },
    { name: '销售毛利', type: 'line', smooth: true, data: trend.value.map(t => t.profit), itemStyle: { color: '#27ae60' } }
  ]
}))

const levelMap = { URGENT: '紧急', HIGH: '高', MID: '中' }
function levelLabel(l) { return levelMap[l] || l }

// =====================================================================
// XK toggle 操作 (写 sys_config.DASHBOARD_CONFIG)
// =====================================================================
async function onToggleChange() {
  const sel = new Set(toggleSelection.value)
  for (const k of Object.keys(sectionEnabled.value)) {
    sectionEnabled.value[k] = sel.has(k)
  }
  persistConfig()
}

function persistConfig() {
  const out = { order: dashOrder.value }
  for (const s of xkToggleSections) out[s.key] = { enabled: !!sectionEnabled.value[s.key] }
  return configApi.updateValue('DASHBOARD_CONFIG', JSON.stringify(out)).catch(() => { /* 静默 — 后端未启 */ })
}

async function onToggleReset() {
  toggleSelection.value = xkToggleSections.filter(s => DEFAULT_DASHBOARD_CONFIG[s.key].enabled).map(s => s.key)
  sectionEnabled.value = {
    compliance: true, kpi: true, productionBoard: true, quality: true,
    maintenance: true, todos: true, quickLinks: true,
    kpiSales: true, trend: true, warning: true, ranking: true
  }
  dashOrder.value = xkToggleSections.map(s => s.key)
  await persistConfig()
}

function goFullConfig() { router.push('/system/settings') }
// v1.1.98: 库存预警 → 采购建议 (基于安全库存缺口一键生成采购单草稿)
function goPurchaseSuggest() { router.push('/purchase/suggest') }
// =====================================================================
// 加载
// =====================================================================
onMounted(async () => {
  // 1. XK section 配置 (enabled + order; v1.1.94 含 4 个新总览子模块)
  try {
    const cr = await configApi.getByKey('DASHBOARD_CONFIG')
    if (cr?.data) {
      const parsed = JSON.parse(cr.data)
      for (const k of Object.keys(sectionEnabled.value)) {
        if (parsed[k] && typeof parsed[k].enabled === 'boolean') {
          sectionEnabled.value[k] = parsed[k].enabled
        }
      }
      // 恢复顺序: 已存 order 在前, 未知键(新增模块)追加在默认位置
      if (Array.isArray(parsed.order) && parsed.order.length) {
        const known = new Set(xkToggleSections.map(s => s.key))
        const ordered = parsed.order.filter(k => known.has(k))
        const missing = xkToggleSections.map(s => s.key).filter(k => !ordered.includes(k))
        dashOrder.value = [...ordered, ...missing]
      }
      toggleSelection.value = Object.keys(sectionEnabled.value).filter(k => sectionEnabled.value[k])
    }
  } catch (e) { /* fallback 默认 */ }

  // 2. ERP 原有 4 section 数据 (v1.1.53 各 section 独立 try/catch 静默)
  if (kpiVisible.value) {
    try {
      const r = await reportApi.dashboard()
      const d = r.data || {}
      kpis.value[0].value = (d.todaySales || 0).toFixed(2)
      kpis.value[1].value = (d.totalSales || 0).toFixed(2)
      kpis.value[2].value = (d.todayPurchase || 0).toFixed(2)
      kpis.value[3].value = (d.arBalance || 0).toFixed(2)
    } catch (e) { /* 静默 */ }
  }
  if (trendVisible.value) {
    try {
      const end = dayjs().format('YYYY-MM-DD')
      const start = dayjs().subtract(29, 'day').format('YYYY-MM-DD')
      const rs = await reportApi.salesSummary({ startDate: start, endDate: end })
      trend.value = rs.data || []
    } catch (e) { /* 静默 */ }
  }
  if (rankingVisible.value) {
    try {
      const end = dayjs().format('YYYY-MM-DD')
      const start = dayjs().subtract(29, 'day').format('YYYY-MM-DD')
      const rk = await reportApi.salesRanking({ startDate: start, endDate: end, limit: 10 })
      ranking.value = rk.data || []
    } catch (e) { /* 静默 */ }
  }
  if (warningVisible.value) {
    try {
      const w = await stockApi.warningList()
      const raw = w.data || []
      warningList.value = raw
        .map(item => ({
          productName: item.product_name || item.productName || '-',
          productCode: item.product_code || item.productCode || '',
          qty: item.qty != null ? Number(item.qty) : 0,
          safetyStock: item.safety_stock != null ? Number(item.safety_stock) : (item.p_safety_stock != null ? Number(item.p_safety_stock) : 0),
          warehouseName: item.wh_name || item.warehouseName || ''
        }))
        .filter(item => item.qty < item.safetyStock)
    } catch (e) { /* 静默 */ }
  }

  // 3. XK 6 section 数据
  try {
    const r = await xkMock.dashboardKpi()
    if (r.code === 200) xkKpi.value = r.data
  } catch (e) { /* 静默 */ }

  // 4. v1.1.119: 吹膜米克重实时 (生产 tab; 无采集器/无数据时卡片自动不渲染)
  // hasPerm 对 SUPER_ADMIN 恒 true, 普通角色需有 mes:weight:list 才拉
  if (userStore.hasPerm('mes:weight:list')) {
    loadWeightData().then(startWeightRefresh)
  }
})

// 离开页面清掉米克重自动刷新定时器
onUnmounted(() => { stopWeightRefresh() })

// erpVisible 变化不持久化 (用户级 localStorage 可选, 一期不写)
</script>

<style scoped lang="scss">
.xk-dashboard { display: flex; flex-direction: column; gap: 12px; position: relative; }

/* 模块 tab 栏 (对齐 Figma 模块切换; 末位齿轮浮窗悬浮其上) */
.dashboard-modules {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  position: relative;
  .dash-module-tab {
    display: inline-flex; align-items: center; gap: 6px;
    padding: 9px 16px; border-radius: 10px; cursor: pointer;
    background: #fff; border: 1px solid var(--line); font-size: 13px; font-weight: 600;
    color: var(--muted); transition: all .18s;
    .el-icon { color: inherit; }
    &:hover { border-color: #cfe0f2; color: var(--text); box-shadow: 0 4px 14px rgba(20,80,150,.08); }
    &.active {
      background: var(--primary-color); border-color: var(--primary-color); color: #fff;
      box-shadow: 0 8px 22px rgba(22,113,217,.22);
      .el-icon { color: #fff; }
    }
  }
}

/* 质量模块 3 卡 */
.quality-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
@media (max-width: 900px) { .quality-grid { grid-template-columns: 1fr; } }
.quality-cell {
  background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 12px; padding: 18px;
  .qc-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  .qc-title { font-size: 13px; font-weight: 700; color: var(--text); }
  .qc-num { font-size: 28px; font-weight: 800; color: var(--text); margin: 14px 0 12px; line-height: 1; }
  .qc-num small { font-size: 11px; color: var(--muted); font-weight: 500; margin-left: 5px; }
  &.qc-blue { border-top: 3px solid var(--primary-color); }
  &.qc-green { border-top: 3px solid var(--success); }
  &.qc-amber { border-top: 3px solid var(--warning); }
}

/* 维保模块: 3 KPI + 待办行 */
.maintain-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 14px; }
@media (max-width: 900px) { .maintain-grid { grid-template-columns: 1fr; } }
.maintain-kpi {
  background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 12px; padding: 16px; text-align: center;
  .mk-num { font-size: 26px; font-weight: 800; color: var(--text); }
  .mk-label { font-size: 11px; color: var(--muted); margin-top: 4px; }
  &.mk-blue { border-left: 4px solid var(--primary-color); }
  &.mk-green { border-left: 4px solid var(--success); }
  &.mk-red { border-left: 4px solid var(--danger); }
}
.maintain-row {
  display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid #eef1f4;
  &:last-child { border-bottom: 0; }
  .maintain-title { flex: 1; font-size: 13px; font-weight: 600; color: var(--text); }
  .maintain-time { font-size: 11px; color: var(--muted); }
}

/* 合规模块: 入口列表 */
.compliance-entries { display: flex; flex-direction: column; gap: 12px; }
.compliance-entry {
  display: flex; align-items: center; gap: 14px; padding: 16px;
  background: #fbfcfe; border: 1px solid #eef2f7; border-radius: 12px; cursor: pointer;
  transition: all .18s; color: var(--primary-color);
  .el-icon { flex: 0 0 auto; }
  &:hover { background: var(--primary-soft, #eef5ff); border-color: #cfe0f2; transform: translateY(-1px); }
  div { flex: 1; }
  strong { display: block; font-size: 13px; font-weight: 650; color: var(--text); }
  small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
}

/* 右上角齿轮按钮 (悬浮在模块 tab 栏右侧) */
.section-toggles-bar {
  position: absolute; right: 0; top: 0; z-index: 10;
}
.toggle-btn { background: #fff; box-shadow: 0 1px 4px rgba(0,21,41,0.12); }
.toggle-btn:hover { background: #f0f7ff; }
.toggle-panel { font-size: 13px; }
.toggle-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 4px; }
.toggle-hint { font-size: 11px; color: #999; margin-bottom: 10px; line-height: 1.5; }
.toggle-section-title { font-size: 12px; color: #888; margin-bottom: 6px; font-weight: 500; }
.toggle-list { display: flex; flex-direction: column; gap: 4px; }
.toggle-item { margin: 0; width: 100%; }
.toggle-icon { margin-right: 4px; vertical-align: middle; }
.toggle-item .disabled { color: #bbb; }
.toggle-footer { margin-top: 10px; text-align: right; border-top: 1px solid #eee; padding-top: 8px; }

/* ERP 原有 4 section 样式 (v1.1.53 沿用) */
.kpi-card .kpi-inner { padding: 12px 14px 12px 12px; }
.kpi-label { font-size: 12px; color: var(--muted); margin-bottom: 3px; }
.kpi-value { font-size: 26px; font-weight: 780; color: var(--text); }
.kpi-value .unit { font-size: 13px; color: var(--muted); font-weight: 500; margin-left: 3px; }
.kpi-sub { font-size: 12px; color: var(--muted); margin-top: 3px; }
.kpi-sub.up { color: var(--success); }
.kpi-sub.down { color: var(--danger); }
.quick-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }

/* XK 新增 6 section 样式 — Figma 视觉 */
.compliance-strip {
  min-height: 54px; padding: 10px 14px;
  display: flex; justify-content: space-between; align-items: center; gap: 12px;
  border: 1px solid #bfd8f2; background: linear-gradient(90deg, #eef6ff, #f8fbff);
  border-radius: 12px; color: #245986;
  .left { display: flex; align-items: center; gap: 11px; }
  .shield { color: var(--primary-color); font-size: 22px; }
  .title { font-size: 12px; font-weight: 700; }
  .sub { font-size: 11px; color: #5c7690; margin-top: 3px; }
}

.xk-kpi-row { margin-top: 12px; }
.xk-kpi-value { font-size: 28px; font-weight: 780; color: var(--text); margin: 4px 0; }
.xk-kpi-value .unit { font-size: 14px; color: var(--muted); font-weight: 500; margin-left: 4px; }

.card-header { display: flex; justify-content: space-between; align-items: center; }
.card-header b { font-size: 15px; font-weight: 730; }
.card-header .sub { font-size: 12px; color: var(--muted); }

/* 生产看板 line-item — Figma .line-row */
.line-item {
  padding: 14px 0; border-bottom: 1px solid #eef1f4;
  &:last-child { border-bottom: none; }
  .line-row { display: flex; align-items: center; gap: 10px; margin-bottom: 9px; }
  .line-icon { color: #60748b; font-size: 18px; }
  .line-name { font-weight: 650; color: var(--text); }
  .line-product { flex: 1; color: var(--muted); font-size: 12px; }
  .line-progress-text { font-weight: 700; color: var(--text); }
}

/* v1.1.119 吹膜米克重实时卡 */
.weight-cell {
  padding: 14px 0; border-bottom: 1px solid #eef1f4;
  &:last-child { border-bottom: none; }
  .weight-cell-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
  .weight-dev-code { color: var(--muted); font-size: 11px; }
  .weight-body { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; }
  .weight-kpis {
    display: grid; grid-template-columns: repeat(4, minmax(96px, 1fr)); gap: 10px; flex: 1; min-width: 300px;
    .wk {
      background: #f6f8fa; border: 1px solid #eef1f4; border-radius: 8px; padding: 10px 12px;
      .wk-num { font-size: 20px; font-weight: 720; color: var(--text); line-height: 1.05;
        small { font-size: 11px; font-weight: 500; color: var(--muted); margin-left: 2px; }
      }
      .wk-label { font-size: 11px; color: var(--muted); margin-top: 4px; }
      .wk-sub { font-size: 10px; color: var(--muted); margin-top: 2px; }
      &.over .wk-num { color: #f56c6c; }
      &.over .wk-sub { color: #f56c6c; font-weight: 600; }
      &.wk-gauge { background: #fff5f5; border-color: #fdd; &.over { background: #fef0f0; } }
    }
  }
  .weight-spark { flex-shrink: 0; text-align: center; }
  .weight-spark-cap { font-size: 10px; color: var(--muted); margin-top: 4px; }
  .weight-empty { color: var(--muted); font-size: 12px; flex: 1; text-align: center; padding: 20px 0; }
}
@media (max-width: 900px) { .weight-kpis { grid-template-columns: repeat(2, 1fr); } }


/* 待办 — Figma .todo-item */
.todo-list { max-height: 380px; overflow-y: auto; }
.todo-item {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 0; border-bottom: 1px solid #eef1f4;
  &:last-child { border-bottom: none; }
  .todo-level { flex-shrink: 0; }
  .todo-content { flex: 1; min-width: 0; }
  .todo-title { font-size: 13px; color: var(--text); font-weight: 550; }
  .todo-sub { font-size: 11px; color: var(--muted); margin-top: 2px; }
  .todo-time { font-size: 11px; color: var(--muted); flex-shrink: 0; }
}

/* XK 快捷入口 — Figma .quick-card */
.quick-card { cursor: pointer; transition: transform .18s; }
.quick-card:hover { transform: translateY(-2px); }
.quick-inner { display: flex; align-items: center; gap: 12px; padding: 14px; }
.quick-icon { font-size: 28px; color: var(--primary-color); flex: 0 0 auto; }
.quick-title { font-size: 13px; font-weight: 650; color: var(--text); }
.quick-sub { font-size: 11px; color: var(--muted); margin-top: 3px; }
</style>
