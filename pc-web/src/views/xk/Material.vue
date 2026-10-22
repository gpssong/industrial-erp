<template>
  <div class="xk-qualified-supplier">
    <!-- 面包屑 + 大标题 + 右侧动作 (对齐 Figma) -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">供应链 / 采购准入控制</div>
        <h1 class="xk-title">合格供应商管理</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button @click="onLog"><el-icon><Clock /></el-icon>业务日志</el-button>
        <el-button type="primary" @click="onAdd"><el-icon><Plus /></el-icon>新增供应商</el-button>
      </div>
    </div>

    <!-- 1. 合格供应商 — 5 项模块入口 (可切换视图) -->
    <div class="supplier-card">
      <div class="supplier-card-head">
        <div>
          <b>合格供应商</b>
          <small>选择模块快速进入 (名录 / 评审 / 证照 / 绩效 / 暂停)</small>
        </div>
        <el-tag size="small" effect="plain">{{ activeLabel }}</el-tag>
      </div>
      <div class="supplier-quick-grid">
        <div
          v-for="l in quickLinks"
          :key="l.key"
          class="supplier-quick-item"
          :class="{ active: activeModule === l.key }"
          @click="onQuick(l)"
        >
          <el-icon class="qi-icon"><component :is="l.icon" /></el-icon>
          <span class="qi-title">{{ l.title }}</span>
          <span class="qi-count" v-if="moduleRowsOf(rows, l.key).length >= 0">{{ moduleRowsOf(rows, l.key).length }}</span>
          <el-icon class="qi-arrow"><ArrowRight /></el-icon>
        </div>
      </div>
    </div>

    <!-- 2. XK 采购准入控制 — 受控条 -->
    <div class="supplier-rule-strip">
      <el-icon class="sr-icon"><CircleCheckFilled /></el-icon>
      <div class="sr-body">
        <strong>XK 采购准入控制</strong>
        <small>食品接触原料仅可向「合格」且资质在有效期内的供应商采购；暂停供货的供应商不能创建采购订单；资质临期 / 复评临近会标黄预警</small>
      </div>
      <span class="sr-badge">名录受控</span>
    </div>

    <!-- 3. XK 复评预警条 (从 remark 解析 食品级 / 有效期 / 复评 到期日, 30 天内临期标红) -->
    <div class="supplier-warn" v-if="xKExpiringList.length > 0">
      <el-icon class="sw-icon"><Warning /></el-icon>
      <div class="sw-body">
        <strong>复评 / 资质临期预警</strong>
        <small>以下供应商资质 / 年度复评在 30 天内到期，建议提前完成现场评审或资质更新</small>
      </div>
      <el-tag type="danger" effect="dark" size="small">{{ xKExpiringList.length }} 家临期</el-tag>
    </div>

    <!-- 4. 模块视图 (当前模块对应的列表) -->
    <div class="supplier-card" v-loading="loading">
      <div class="supplier-card-head">
        <div>
          <b>{{ activeLabel }}</b>
          <small class="module-desc">{{ currentDesc }}</small>
        </div>
        <div class="supplier-head-actions">
          <el-input
            v-model="keyword"
            clearable
            style="width: 240px"
            :placeholder="searchPlaceholder"
            @keyup.enter="onSearch"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button plain @click="onSearch"><el-icon><Search /></el-icon>查询</el-button>
          <el-button plain @click="onExport"><el-icon><Download /></el-icon>导出</el-button>
        </div>
      </div>

      <el-table :data="rows" size="default" empty-text="暂无数据">
        <!-- 名录 / 评审 / 证照: 全字段 -->
        <template v-if="activeModule !== 'pause' && activeModule !== 'rate'">
          <el-table-column prop="supplierCode" label="供应商编码" width="130">
            <template #default="{ row }"><span style="font-weight:700;color:var(--text)">{{ row.supplierCode || '-' }}</span></template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商名称" min-width="150">
            <template #default="{ row }">
              <div style="font-weight:600;color:var(--text)">{{ row.supplierName }}</div>
              <div class="sub-cell">{{ row.contactPerson ? `联系人 ${row.contactPerson}` : '—' }}{{ row.phone ? ' · ' + row.phone : '' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="批准供货范围" min-width="130">
            <template #default="{ row }"><span class="sub-cell">{{ typeLabel(row.supplierType) }}</span></template>
          </el-table-column>
          <el-table-column label="食品级资质" min-width="180">
            <template #default="{ row }">
              <el-tag v-if="hasFoodGrade(row)" type="success" size="small" effect="light">食品级 ✓</el-tag>
              <el-tag v-else type="info" size="small" effect="plain">未声明</el-tag>
              <div class="sub-cell" :class="{ 'warn-text': isExpiringSoon(row) }" v-if="extractCertExpiry(row)">
                资质至 {{ extractCertExpiry(row) }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="资质 / 证照" min-width="180">
            <template #default="{ row }">
              <span class="sub-cell" :class="{ empty: !row.remark }">{{ row.remark || '暂无证照档案' }}</span>
            </template>
          </el-table-column>
        </template>

        <!-- 绩效评价: 强调评分 + XK 复评状态 -->
        <template v-else-if="activeModule === 'rate'">
          <el-table-column prop="supplierCode" label="供应商编码" width="130">
            <template #default="{ row }"><span style="font-weight:700;color:var(--text)">{{ row.supplierCode || '-' }}</span></template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商名称" min-width="150">
            <template #default="{ row }">
              <div style="font-weight:600;color:var(--text)">{{ row.supplierName }}</div>
              <div class="sub-cell">食品级 · {{ hasFoodGrade(row) ? '已声明' : '未声明' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="年度评价" min-width="120">
            <template #default="{ row }">
              <el-tag v-if="rateOf(row)" :type="rateType(row)" size="small">{{ rateOf(row) }}</el-tag>
              <el-tag v-else type="info" size="small">未评级</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="下次复评" min-width="130">
            <template #default="{ row }">
              <span class="sub-cell" :class="{ 'warn-text': isReviewDueSoon(row) }">{{ extractReviewDate(row) || '未排期' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="评价依据 / 备注" min-width="200">
            <template #default="{ row }"><span class="sub-cell" :class="{ empty: !row.remark }">{{ row.remark || '暂无年度评价记录' }}</span></template>
          </el-table-column>
        </template>

        <!-- 暂停与恢复: 只列暂停供货的, 带恢复按钮 + 暂停原因 -->
        <template v-else>
          <el-table-column prop="supplierCode" label="供应商编码" width="130">
            <template #default="{ row }"><span style="font-weight:700;color:var(--text)">{{ row.supplierCode || '-' }}</span></template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商名称" min-width="180">
            <template #default="{ row }">
              <div style="font-weight:600;color:var(--text)">{{ row.supplierName }}</div>
              <div class="sub-cell">{{ row.contactPerson ? `联系人 ${row.contactPerson}` : '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="食品级资质" min-width="120">
            <template #default="{ row }">
              <el-tag v-if="hasFoodGrade(row)" type="success" size="small" effect="light">食品级 ✓</el-tag>
              <el-tag v-else type="info" size="small" effect="plain">未声明</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="暂停原因 / 备注" min-width="220">
            <template #default="{ row }"><span class="sub-cell" :class="{ empty: !row.remark }">{{ row.remark || '—' }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="140" align="center">
            <template #default="{ row }">
              <el-button link type="success" @click="onResume(row)">恢复供货</el-button>
            </template>
          </el-table-column>
        </template>

        <!-- 状态列 (暂停视图除外, 其它视图都显示) -->
        <el-table-column v-if="activeModule !== 'pause'" label="准入状态" width="120" align="center">
          <template #default="{ row }">
            <span class="rec-pill" :class="row.status === 1 ? 'ok' : 'danger'">{{ row.status === 1 ? '合格' : '暂停供货' }}</span>
          </template>
        </el-table-column>

        <!-- 名录/评审/证照 视图的操作列 -->
        <el-table-column v-if="activeModule !== 'pause' && activeModule !== 'rate'" label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="module-empty-tip" v-if="!loading && rows.length === 0">
        {{ emptyTip }}
      </div>
      <el-pagination
        v-if="showPaging"
        class="pager"
        background
        layout="total, prev, pager, next, jumper"
        :total="Number(total)"
        :current-page="query.pageNum"
        :page-size="query.pageSize"
        @current-change="queryOnPageChange"
      />
    </div>

    <!-- 5. 经营 AI 助手 -->
    <div class="ai-card">
      <div class="ai-left">
        <el-icon :size="22" color="var(--primary-color)"><ChatDotRound /></el-icon>
        <div>
          <b>经营 AI 助手</b>
          <small>可以询问「本月食品袋销售额」「哪些原料库存不足」或「待收款客户」</small>
        </div>
      </div>
      <el-button text type="primary" @click="router.push('/report/ai')">
        开始提问 <el-icon><ArrowRight /></el-icon>
      </el-button>
    </div>

    <!-- 编辑 / 新增 弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑供应商' : '新增供应商'" width="700px" destroy-on-close>
      <el-form :model="form" label-width="100px">
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="编码"><el-input v-model="form.supplierCode" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="名称"><el-input v-model="form.supplierName" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12"><el-form-item label="联系人"><el-input v-model="form.contactPerson" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="税率(%)"><el-input-number v-model="form.taxRate" :precision="2" :step-strictly="false" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="外协厂"><el-switch v-model="form.isOutsource" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="税号"><el-input v-model="form.taxNo" /></el-form-item></el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="8"><el-form-item label="供货范围"><el-select v-model="form.supplierType"><el-option v-for="(v,k) in typeMap" :key="k" :label="v" :value="k" /></el-select></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="准入状态"><el-switch v-model="form.status" active-text="合格" inactive-text="暂停供货" :active-value="1" :inactive-value="0" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="XK 资质/评价">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="4"
            placeholder="XK 食品接触原料供应商资质档案，示例：食品级 资质有效至 2026-12 / 下次复评 2026-09 / 年度评价 A 级 (92 分)"
          />
          <div class="form-hint">支持自动解析关键字：<b>食品级</b> / 资质有效至 YYYY-MM / 下次复评 YYYY-MM / 年度评价 A/B/C 级 / NN 分</div>
        </el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight, Clock, Plus, Search, Download, ChatDotRound,
  CircleCheckFilled, Tickets, Files, Star, Warning
} from '@element-plus/icons-vue'
import { supplierApi } from '@/api/base'

const router = useRouter()

// 5 个模块 (对应 XK 食品接触塑料吹膜厂 XK16-204 采购准入合规语义)
// 全部基于 base_supplier 真实数据, 用 status / supplierType / remark 字段承载 XK 合规语义 (零 DDL)
const quickLinks = [
  { key: 'list',   title: '合格供应商名录',   icon: 'Tickets',           desc: '全部合格/暂停供应商, 增删改查统一维护 — 食品接触原料供应商完整档案' },
  { key: 'review', title: 'XK 准入评审',      icon: 'CircleCheckFilled', desc: '食品级资质 / 检测报告有效性, 缺资质或临期标「待复评」' },
  { key: 'docs',   title: '证照与检测报告',   icon: 'Files',             desc: '食品级资质证书 / 第三方检测报告留档, 解析有效期自动预警' },
  { key: 'rate',   title: '年度绩效评价',     icon: 'Star',              desc: 'XK 绩效分级 (A/B/C 级), 含下次复评日期, 临期标黄' },
  { key: 'pause',  title: '暂停与恢复供货',   icon: 'Warning',           desc: '暂停供货的供应商, 公示暂停原因, 可一键恢复为合格' }
]

const activeModule = ref('list')
const activeLabel = computed(() => quickLinks.find(l => l.key === activeModule.value)?.title || '合格供应商名录')
const currentDesc = computed(() => quickLinks.find(l => l.key === activeModule.value)?.desc || '')

function onQuick(l) {
  activeModule.value = l.key
  keyword.value = ''
  query.pageNum = 1
  loadData()
}

// 搜索框 placeholder 随模块变化 (XK 主题)
const searchPlaceholder = computed(() => {
  if (activeModule.value === 'pause') return '搜索暂停供货供应商 / 食品级关键字'
  if (activeModule.value === 'rate') return '搜索供应商 / 评价等级 / 复评日期'
  if (activeModule.value === 'review') return '搜索供应商 / 食品级资质'
  return '搜索供应商、原料或资质'
})

const emptyTip = computed(() => {
  if (activeModule.value === 'pause') return '没有暂停供货的供应商 — 名录中全部为合格状态'
  if (activeModule.value === 'rate') return '暂无 XK 年度绩效评价记录 — 评审后会自动归入此模块'
  if (activeModule.value === 'review') return '暂无待评审的 XK 食品级供应商'
  if (activeModule.value === 'docs') return '暂无证照档案 — 在「编辑」中填写食品级资质 / 检测报告'
  return '暂无供应商'
})

// 数据 (共享一份 base_supplier, 不同模块按前端过滤)
const query = reactive({ pageNum: 1, pageSize: 50, keyword: '' })
const keyword = ref('')
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const dialogVisible = ref(false)
const form = reactive({
  id: null, supplierCode: '', supplierName: '',
  isOutsource: 0, contactPerson: '', phone: '', address: '',
  supplierType: 'NORMAL', taxRate: 13.00, status: 1, remark: ''
})

const typeMap = { NORMAL: '通用原料', MANUFACTURER: '自产供应商', AGENT: '经销代理商' }
function typeLabel(t) { return typeMap[t] || '通用原料' }

// ====== XK 字段解析 (从 remark 文本提取) ======

// 食品级关键字: 含「食品级」/「食品接触」/「食品用」 之一即视为食品级
function hasFoodGrade(row) {
  if (!row.remark) return false
  return /食品级|食品接触|食品用|FDA|GMP|HACCP/i.test(row.remark)
}

// 资质有效期: 解析「有效至 2026-12」/「有效期 2026-12-31」/「至 2026/12」
function extractCertExpiry(row) {
  if (!row.remark) return ''
  const m = row.remark.match(/(?:有效[期至]*至?|至|过期)\s*[:：]?\s*(\d{4})\s*[-/.年]\s*(\d{1,2})(?:[-/.月]\s*(\d{1,2})\s*[日日]?)?/)
  if (!m) return ''
  const y = m[1], mo = String(m[2]).padStart(2, '0'), d = m[3] ? String(m[3]).padStart(2, '0') : '01'
  return `${y}-${mo}-${d}`
}

// 下次复评日期: 解析「下次复评 2026-09」/「复评日期 2026/9/15」
function extractReviewDate(row) {
  if (!row.remark) return ''
  const m = row.remark.match(/(?:下次)?复评[日期]*\s*[:：]?\s*(\d{4})\s*[-/.年]\s*(\d{1,2})(?:[-/.月]\s*(\d{1,2})\s*[日日]?)?/)
  if (!m) return ''
  const y = m[1], mo = String(m[2]).padStart(2, '0'), d = m[3] ? String(m[3]).padStart(2, '0') : '01'
  return `${y}-${mo}-${d}`
}

// 临期判定: 资质/复评 30 天内到期 (基于今天)
function isWithin30Days(dateStr) {
  if (!dateStr) return false
  const target = new Date(dateStr)
  if (isNaN(target.getTime())) return false
  const now = new Date()
  const diff = (target - now) / (1000 * 60 * 60 * 24)
  return diff >= 0 && diff <= 30
}
function isExpiringSoon(row) { return isWithin30Days(extractCertExpiry(row)) }
function isReviewDueSoon(row) { return isWithin30Days(extractReviewDate(row)) }

// 从 remark 提取年度评价级别 (A级/B级/C级 或 含分数)
function rateOf(row) {
  if (!row.remark) return ''
  const m = row.remark.match(/([ABCD])\s*级/); if (m) return m[1] + ' 级'
  const n = row.remark.match(/(\d{2,3})\s*分/); if (n) return n[1] + ' 分'
  return ''
}
function rateType(row) {
  const r = rateOf(row)
  if (!r) return 'info'
  if (r.startsWith('A')) return 'success'
  if (r.startsWith('B')) return 'primary'
  if (r.startsWith('C')) return 'warning'
  return 'info'
}

// XK 复评/资质临期列表 (用于顶部红条)
const xKExpiringList = computed(() => {
  return rows.value.filter(r =>
    r.status === 1 && (isExpiringSoon(r) || isReviewDueSoon(r))
  )
})

// 各模块的行过滤 (在 keyword 服务端过滤 + status 基础上再分模块)
function moduleRowsOf(all, key) {
  switch (key) {
    case 'list': return all
    case 'review': // XK 准入评审: 合格的 + 缺食品级声明或资质临期 = 待复评
      return all.filter(r => r.status === 1 && (!hasFoodGrade(r) || isExpiringSoon(r) || isReviewDueSoon(r)))
    case 'docs': // 证照与检测报告: 有食品级声明 或 有资质/复评日期
      return all.filter(r => r.remark && r.remark.trim() && (hasFoodGrade(r) || extractCertExpiry(r) || extractReviewDate(r)))
    case 'rate': // 年度绩效评价: 能解析出评价的
      return all.filter(r => rateOf(r))
    case 'pause': // 暂停与恢复: 状态 = 暂停
      return all.filter(r => r.status !== 1)
    default: return all
  }
}
function moduleRows(all) { return moduleRowsOf(all, activeModule.value) }

const filteredRows = computed(() => moduleRows(rows.value))
const showPaging = computed(() => activeModule.value === 'list')

async function loadData() {
  loading.value = true
  try {
    query.keyword = keyword.value
    const r = await supplierApi.page(query)
    const d = r?.data || {}
    rows.value = d.records || []
    total.value = d.total || rows.value.length
  } catch (e) {
    console.warn('[Material] load fail:', e)
    rows.value = []
    total.value = 0
  } finally { loading.value = false }
}
// v1.1.112 (R20): el-pagination handler 必须显式接参, 否则 pageNum 不更新, 点第 2 页无响应
function queryOnPageChange(p) { if (p === query.pageNum) return; query.pageNum = p; loadData() }
function queryOnSizeChange(s) { if (s === query.pageSize) return; query.pageSize = s; query.pageNum = 1; loadData() }
function onSearch() { keyword.value = keyword.value; query.pageNum = 1; loadData() }
function onExport() {
  // 简单 TSV 导出 (后端无导出 API 时, 走前端下载, 含 XK 字段)
  const headers = ['编码', '名称', '供货范围', '联系人', '电话', '准入状态', '食品级', '资质有效期', '下次复评', '年度评价', '备注']
  const data = rows.value.map(r => [
    r.supplierCode || '',
    r.supplierName || '',
    typeLabel(r.supplierType),
    r.contactPerson || '',
    r.phone || '',
    r.status === 1 ? '合格' : '暂停',
    hasFoodGrade(r) ? '食品级' : '—',
    extractCertExpiry(r) || '—',
    extractReviewDate(r) || '—',
    rateOf(r) || '未评级',
    (r.remark || '').replace(/[\t\r\n]+/g, ' ')
  ])
  const tsv = [headers, ...data].map(row => row.join('\t')).join('\n')
  const blob = new Blob(['﻿' + tsv], { type: 'text/tab-separated-values;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `合格供应商名录-${new Date().toISOString().slice(0, 10)}.tsv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${rows.value.length} 条供应商 (含 XK 字段)`)
}

function onAdd() {
  Object.assign(form, { id: null, supplierCode: '', supplierName: '', supplierType: 'NORMAL', status: 1, taxRate: 13.00, isOutsource: 0, remark: '', contactPerson: '', phone: '', taxNo: '', address: '' })
  dialogVisible.value = true
}
function onEdit(row) { Object.assign(form, { ...row }); dialogVisible.value = true }
async function onDelete(row) {
  await ElMessageBox.confirm(`删除 ${row.supplierName}?`, '提示', { type: 'warning' })
  try { await supplierApi.delete(row.id); ElMessage.success('已删除'); loadData() }
  catch (e) { ElMessage.error(e.message || '删除失败') }
}
async function onSave() {
  try {
    if (form.id) await supplierApi.update(form); else await supplierApi.add(form)
    ElMessage.success('已保存'); dialogVisible.value = false; loadData()
  } catch (e) { ElMessage.error(e.message || '保存失败') }
}
async function onResume(row) {
  try {
    await ElMessageBox.confirm(`恢复 ${row.supplierName} 为合格供应商? 恢复后即可创建采购订单。`, '恢复供货', { type: 'warning', confirmButtonText: '恢复' })
    await supplierApi.update({ ...row, status: 1 })
    ElMessage.success(`已恢复 ${row.supplierName}`)
    loadData()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || '恢复失败') }
}

function onLog() { router.push('/report/recycle') }

onMounted(loadData)
watch(rows, () => {}, { immediate: false })
</script>

<style scoped lang="scss">
.xk-qualified-supplier { display: flex; flex-direction: column; gap: 16px; }

.supplier-card {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 18px 20px;
  box-shadow: 0 1px 2px rgba(15, 40, 70, .04);
}
.supplier-card-head {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px; margin-bottom: 14px;
}
.supplier-card-head b { font-size: 15px; font-weight: 730; color: var(--text); }
.supplier-card-head small { display: block; font-size: 11px; color: var(--muted); margin-top: 3px; }
.supplier-card-head .module-desc { color: var(--muted); }
.supplier-head-actions { display: flex; align-items: center; gap: 10px; }

// 5 项模块入口 (复用 .quick-grid 视觉)
.supplier-quick-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
@media (max-width: 1200px) {
  .supplier-quick-grid { grid-template-columns: repeat(2, 1fr); }
}
.supplier-quick-item {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px;
  background: #fbfcfe;
  border: 1px solid #eef2f7;
  border-radius: 11px;
  cursor: pointer;
  transition: all .18s;
  font-size: 13px; font-weight: 550; color: var(--text);
  &:hover {
    background: var(--primary-color); color: #fff; border-color: var(--primary-color);
    .qi-icon, .qi-arrow, .qi-count { color: #fff; border-color: #fff; }
  }
  &.active {
    background: #eef5ff; border-color: var(--primary-color);
    color: var(--primary-color); font-weight: 700;
    .qi-icon { color: var(--primary-color); }
  }
}
.qi-icon { color: var(--primary-color); flex: 0 0 auto; font-size: 16px; }
.qi-title { flex: 1; }
.qi-arrow { color: #b6c2d0; }
// 5 模块实时计数角标 (XK 视图, 数据从 base_supplier 前端过滤)
.qi-count {
  display: inline-block; min-width: 22px; height: 20px; line-height: 18px;
  padding: 0 6px; border-radius: 10px;
  background: #fff; color: var(--primary-color);
  border: 1px solid var(--primary-color);
  font-size: 11px; font-weight: 700; text-align: center;
  flex: 0 0 auto;
  transition: all .18s;
}

// XK 采购准入控制受控条
.supplier-rule-strip {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 18px;
  background: linear-gradient(90deg, #e9f7ee, #f1faf3);
  border: 1px solid #b8e2c4;
  border-radius: 12px;
}
.supplier-rule-strip .sr-icon {
  width: 32px; height: 32px;
  display: grid; place-items: center;
  background: #fff; color: #2ca767;
  border-radius: 9px; font-size: 16px;
  flex: 0 0 auto;
}
.supplier-rule-strip .sr-body { flex: 1; min-width: 0; }
.supplier-rule-strip .sr-body strong { display: block; font-size: 14px; font-weight: 730; color: #1c6e3e; }
.supplier-rule-strip .sr-body small { display: block; font-size: 11px; color: #4d6b58; margin-top: 3px; line-height: 1.55; }
.supplier-rule-strip .sr-badge {
  background: #fff; color: #1c6e3e;
  border: 1px solid #b8e2c4;
  font-size: 11px; font-weight: 700;
  padding: 4px 10px; border-radius: 999px;
  flex: 0 0 auto;
}

// XK 复评/资质临期预警条 (30 天内)
.supplier-warn {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 18px;
  background: linear-gradient(90deg, #fff4ed, #fff8f1);
  border: 1px solid #f5b783;
  border-radius: 12px;
}
.supplier-warn .sw-icon {
  width: 32px; height: 32px;
  display: grid; place-items: center;
  background: #fff; color: #d96a2b;
  border-radius: 9px; font-size: 16px;
  flex: 0 0 auto;
}
.supplier-warn .sw-body { flex: 1; min-width: 0; }
.supplier-warn .sw-body strong { display: block; font-size: 13px; font-weight: 730; color: #8a3e0f; }
.supplier-warn .sw-body small { display: block; font-size: 11px; color: #a36b48; margin-top: 2px; line-height: 1.5; }

// 表格
.supplier-card :deep(.el-table) {
  border: 1px solid #eef2f7;
  border-radius: 9px;
  overflow: hidden;
}
.sub-cell { font-size: 12px; color: var(--muted);
  &.empty { color: #c0c8d0; font-style: italic; }
  &.warn-text { color: #d96a2b; font-weight: 650; }
}
// 准入状态 pill
.rec-pill {
  display: inline-block; padding: 3px 12px; border-radius: 999px;
  font-size: 11px; font-weight: 700; line-height: 1.4;
  &.ok { background: #e6f7ec; color: #1f8a4c; }
  &.danger { background: #ffeded; color: #d63b3b; }
}
.module-empty-tip {
  padding: 18px 4px; text-align: center;
  font-size: 12px; color: var(--muted);
}
.pager { margin-top: 12px; text-align: right; }

// 编辑弹窗 XK 字段提示
.form-hint {
  font-size: 11px; color: var(--muted);
  margin-top: 4px; line-height: 1.5;
  b { color: var(--primary-color); font-weight: 600; }
}

// 经营 AI 助手
.ai-card {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  background: linear-gradient(90deg, #eef6ff, #f8fbff);
  border: 1px solid #bfd8f2;
  border-radius: 14px;
  padding: 18px 20px;
}
.ai-left { display: flex; align-items: center; gap: 14px; }
.ai-left b { display: block; font-size: 14px; font-weight: 700; color: #245986; }
.ai-left small { display: block; font-size: 11px; color: #5c7690; margin-top: 3px; }
</style>
