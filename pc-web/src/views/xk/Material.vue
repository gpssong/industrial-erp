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
          <span class="qi-count">{{ countOf(l.key) }}</span>
          <el-icon class="qi-arrow"><ArrowRight /></el-icon>
        </div>
      </div>
    </div>

    <!-- 2. XK 采购准入控制 — 受控条 -->
    <div class="supplier-rule-strip">
      <el-icon class="sr-icon"><CircleCheckFilled /></el-icon>
      <div class="sr-body">
        <strong>XK 采购准入控制</strong>
        <small>食品接触原料仅可向「合格」且资质在有效期内的供应商采购；暂停供货 / 资质过期 / 评审不通过均不能创建采购订单；证照临期与复评到期提前 90 天预警</small>
      </div>
      <span class="sr-badge">名录受控</span>
    </div>

    <!-- 3. XK 到期看板 (证照临期 / 复评到期 / 年度评级分布) -->
    <div class="supplier-card">
      <div class="supplier-card-head">
        <div>
          <b>XK 到期看板</b>
          <small>证照 90 天内到期 / 复评到期未排期 / 最新年度评级分布 — 来自证照、评审、绩效三张记录表</small>
        </div>
        <el-button link type="primary" @click="refreshAll">刷新</el-button>
      </div>
      <div class="board-grid">
        <div class="board-tile bt-amber">
          <span class="bt-label">证照临期 / 过期</span>
          <span class="bt-num">{{ dash.certDueCount }}<small>份</small></span>
          <span class="bt-note">含已过期, 换证后重新登记</span>
        </div>
        <div class="board-tile bt-red">
          <span class="bt-label">复评到期</span>
          <span class="bt-num">{{ dash.reviewDueCount }}<small>家</small></span>
          <span class="bt-note">含从未评审的合格供应商</span>
        </div>
        <div class="board-tile bt-green">
          <span class="bt-label">年度评级 A / B / C</span>
          <span class="bt-num">{{ ratingDist.A }}<small>/ {{ ratingDist.B }} / {{ ratingDist.C }}</small></span>
          <span class="bt-note">每家最近一次绩效评分</span>
        </div>
      </div>
      <div class="board-warn" v-if="dash.certs.length > 0">
        <el-icon class="bw-icon"><Warning /></el-icon>
        <div class="bw-body">
          <b>证照临期</b>
          <small>{{ dash.certs.slice(0, 3).map(c => `${c.supplierName}·${c.certTypeLabel}(${c.daysLeft < 0 ? '已过期' : c.daysLeft + '天'})`).join('、') }}{{ dash.certs.length > 3 ? ' 等' : '' }}</small>
        </div>
      </div>
      <div class="board-warn bw-review" v-if="dash.reviews.length > 0">
        <el-icon class="bw-icon"><Warning /></el-icon>
        <div class="bw-body">
          <b>复评到期</b>
          <small>{{ dash.reviews.slice(0, 3).map(r => `${r.supplierName}(${r.neverReviewed ? '从未评审' : (r.nextReviewDate || '未排期')})`).join('、') }}{{ dash.reviews.length > 3 ? ' 等' : '' }}</small>
        </div>
      </div>
    </div>

    <!-- 4. 名录 / 暂停视图 -->
    <div v-if="isSupplierView" class="supplier-card" v-loading="loading">
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

      <el-table :data="filteredRows" size="default" empty-text="暂无数据">
        <!-- 名录: 结构化资质 + 最新评审 / 评级 -->
        <template v-if="activeModule === 'list'">
          <el-table-column prop="supplierCode" label="供应商编码" width="130">
            <template #default="{ row }"><span style="font-weight:700;color:var(--text)">{{ row.supplierCode || '-' }}</span></template>
          </el-table-column>
          <el-table-column prop="supplierName" label="供应商名称" min-width="160">
            <template #default="{ row }">
              <div style="font-weight:600;color:var(--text)">{{ row.supplierName }}</div>
              <div class="sub-cell">{{ row.contactPerson ? `联系人 ${row.contactPerson}` : '—' }}{{ row.phone ? ' · ' + row.phone : '' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="批准供货范围" min-width="120">
            <template #default="{ row }"><span class="sub-cell">{{ typeLabel(row.supplierType) }}</span></template>
          </el-table-column>
          <el-table-column label="食品级资质" min-width="180">
            <template #default="{ row }">
              <el-tag v-if="row.foodCertNo" type="success" size="small" effect="light">食品级 ✓</el-tag>
              <el-tag v-else type="info" size="small" effect="plain">未登记</el-tag>
              <div class="sub-cell" :class="{ 'warn-text': certExpiringSoon(row) }">
                {{ row.foodCertNo ? (row.foodCertExp ? '资质至 ' + row.foodCertExp : '未登记有效期') : '—' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="年度评价" width="120" align="center">
            <template #default="{ row }">
              <template v-if="ratingOf(row)">
                <el-tag :type="gradeType(ratingOf(row).grade)" size="small">{{ ratingOf(row).grade }} 级</el-tag>
                <div class="sub-cell">{{ num(ratingOf(row).totalScore) }} 分 · {{ ratingOf(row).evalYear }}</div>
              </template>
              <span v-else class="sub-cell">未评级</span>
            </template>
          </el-table-column>
          <el-table-column label="下次复评" width="130" align="center">
            <template #default="{ row }">
              <span class="sub-cell" :class="{ 'warn-text': reviewDueSoon(row) }">
                {{ reviewOf(row) ? (reviewOf(row).nextReviewDate || '未排期') : '从未评审' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="准入状态" width="110" align="center">
            <template #default="{ row }">
              <span class="rec-pill" :class="row.status === 1 ? 'ok' : 'danger'">{{ row.status === 1 ? '合格' : '暂停供货' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="onEdit(row)">编辑</el-button>
              <el-button v-if="row.status === 1" link type="warning" @click="openSuspend(row)">暂停</el-button>
              <el-button link type="danger" @click="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </template>

        <!-- 暂停与恢复: 只列暂停供货的, 带恢复按钮 + 台账原因 -->
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
              <el-tag v-if="row.foodCertNo" type="success" size="small" effect="light">食品级 ✓</el-tag>
              <el-tag v-else type="info" size="small" effect="plain">未登记</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="暂停原因" min-width="220">
            <template #default="{ row }">
              <div class="sub-cell">{{ suspendLogOf(row) ? `[${reasonTypeLabel(suspendLogOf(row).reasonType)}] ${suspendLogOf(row).reason}` : (row.remark || '—') }}</div>
              <div class="sub-cell" v-if="suspendLogOf(row)">{{ suspendLogOf(row).effectiveDate || '—' }} 生效</div>
            </template>
          </el-table-column>
          <el-table-column label="操作人 / 时间" width="190">
            <template #default="{ row }">
              <span v-if="suspendLogOf(row)" class="sub-cell">{{ suspendLogOf(row).operatorName || '—' }}<br/>{{ (suspendLogOf(row).operTime || '').replace('T', ' ').slice(0, 16) }}</span>
              <span v-else class="sub-cell">历史数据</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" align="center">
            <template #default="{ row }">
              <el-button link type="success" @click="onResume(row)">恢复供货</el-button>
            </template>
          </el-table-column>
        </template>
      </el-table>
      <div class="module-empty-tip" v-if="!loading && filteredRows.length === 0">
        {{ emptyTip }}
      </div>
      <el-pagination
        v-if="activeModule === 'list'"
        class="pager"
        background
        layout="total, prev, pager, next, jumper"
        :total="Number(total)"
        :current-page="query.pageNum"
        :page-size="query.pageSize"
        @current-change="queryOnPageChange"
      />
    </div>

    <!-- 5. 评审 / 证照 / 绩效: 独立记录表 -->
    <SupplierReviewPanel v-else-if="activeModule === 'review'" :suppliers="supplierOptions" @changed="refreshAll" />
    <SupplierCertPanel v-else-if="activeModule === 'docs'" :suppliers="supplierOptions" @changed="refreshAll" />
    <SupplierPerfPanel v-else-if="activeModule === 'rate'" :suppliers="supplierOptions" @changed="refreshAll" />

    <!-- 6. 经营 AI 助手 -->
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
          <el-col :span="8">
            <el-form-item label="准入状态">
              <el-switch v-if="!form.id" v-model="form.status" active-text="合格" inactive-text="暂停供货" :active-value="1" :inactive-value="0" />
              <div v-else>
                <el-tag :type="form.status === 1 ? 'success' : 'danger'" size="small">{{ form.status === 1 ? '合格' : '暂停供货' }}</el-tag>
                <div class="form-hint">状态变更请走列表「暂停 / 恢复供货」, 以保留台账</div>
              </div>
            </el-form-item>
          </el-col>
        </el-row>
        <el-divider content-position="left">食品接触原料资质 (XK 准入)</el-divider>
        <el-row :gutter="12">
          <el-col :span="10"><el-form-item label="食品级证明编号"><el-input v-model="form.foodCertNo" placeholder="食品相关产品生产许可证 / 食品接触原料证明号" /></el-form-item></el-col>
          <el-col :span="7"><el-form-item label="资质有效期至"><el-date-picker v-model="form.foodCertExp" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="可空" /></el-form-item></el-col>
          <el-col :span="7"><el-form-item label="资质类型"><el-select v-model="form.foodCertType" clearable placeholder="可空" style="width:100%">
            <el-option label="食品级原料" value="FOOD_GRADE_RAW" />
            <el-option label="食品相关产品生产许可" value="FOOD_PRODUCT_LICENSE" />
            <el-option label="食品接触级 (其他)" value="FOOD_CONTACT" />
          </el-select></el-form-item></el-col>
        </el-row>
        <div class="form-hint" style="margin: -6px 0 12px 100px">
          完整证照与检测报告请到「证照与检测报告」登记 — 生产许可类保存后会自动同步这里的编号与有效期, 采购订单按同步值做门禁校验
        </div>
        <el-form-item label="资质/评价备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" placeholder="自由备注, 如: 2026 年度现场审核整改已闭环" />
        </el-form-item>
        <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 暂停供货弹窗 — 原因必填, 落 xk_supplier_status_log 台账 -->
    <el-dialog v-model="suspendVisible" title="暂停供货" width="560px" destroy-on-close>
      <div class="suspend-target" v-if="suspendRow">
        <b>{{ suspendRow.supplierName }}</b>
        <span class="sub-cell">{{ suspendRow.supplierCode || '无编码' }}{{ suspendRow.contactPerson ? ' · ' + suspendRow.contactPerson : '' }}</span>
      </div>
      <el-form :model="suspendForm" label-width="96px">
        <el-form-item label="暂停原因" required>
          <el-select v-model="suspendForm.reasonType" style="width:100%">
            <el-option v-for="(v, k) in suspendReasonTypes" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因说明" required>
          <el-input v-model="suspendForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="写明具体事实与依据, 如: 2026-10 批次 GB 4806 迁移量检测不合格" />
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="suspendForm.effectiveDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="默认今天" />
        </el-form-item>
        <el-form-item label="证据附件">
          <el-upload :show-file-list="false" :http-request="onSuspendUpload" accept=".pdf,.jpg,.jpeg,.png,.doc,.docx">
            <el-button size="small" plain :loading="suspendUploading"><el-icon><Upload /></el-icon>上传检测报告 / 通知</el-button>
          </el-upload>
          <div class="form-hint" v-if="suspendForm.evidenceUrl">已上传: <a :href="suspendForm.evidenceUrl" target="_blank">查看附件</a></div>
          <div class="form-hint" v-else>可选; 建议附第三方检测报告或不合格通知 (保存 ≥ 保质期满后 6 个月)</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="suspendVisible = false">取消</el-button>
        <el-button type="danger" :loading="suspendSaving" @click="submitSuspend">确认暂停</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight, Clock, Plus, Search, Download, ChatDotRound,
  CircleCheckFilled, Tickets, Files, Star, Warning, Upload
} from '@element-plus/icons-vue'
import { supplierApi } from '@/api/base'
import { xkSupplierApi } from '@/api/xk'
import { uploadFile } from '@/api/workflow'
import SupplierReviewPanel from './components/SupplierReviewPanel.vue'
import SupplierCertPanel from './components/SupplierCertPanel.vue'
import SupplierPerfPanel from './components/SupplierPerfPanel.vue'

const router = useRouter()

// 5 个模块 (对应 XK 食品接触塑料吹膜厂 XK16-204 采购准入合规语义)
const quickLinks = [
  { key: 'list',   title: '合格供应商名录',   icon: 'Tickets',           desc: '全部合格/暂停供应商, 增删改查统一维护 — 食品接触原料供应商完整档案' },
  { key: 'review', title: 'XK 准入评审',      icon: 'CircleCheckFilled', desc: '初次准入 / 年度复评 / 专项评审逐次留档; 结论不通过将阻断食品级采购订单' },
  { key: 'docs',   title: '证照与检测报告',   icon: 'Files',             desc: '生产许可 / GB 4806 检测报告 / 符合性声明, 到期 90 天预警' },
  { key: 'rate',   title: '年度绩效评价',     icon: 'Star',              desc: '5 维度加权评分 → A/B/C 分级, 自动推算下次复评日' },
  { key: 'pause',  title: '暂停与恢复供货',   icon: 'Warning',           desc: '暂停供货的供应商, 带暂停原因台账, 恢复需填写整改理由' }
]

const activeModule = ref('list')
const activeLabel = computed(() => quickLinks.find(l => l.key === activeModule.value)?.title || '合格供应商名录')
const currentDesc = computed(() => quickLinks.find(l => l.key === activeModule.value)?.desc || '')
const isSupplierView = computed(() => activeModule.value === 'list' || activeModule.value === 'pause')

function onQuick(l) {
  activeModule.value = l.key
  keyword.value = ''
  query.pageNum = 1
  // 名录分页; 暂停视图一次取全量再前端过滤 (供应商接口无 status 过滤参数)
  query.pageSize = l.key === 'list' ? 50 : 500
  if (isSupplierView.value) loadData()
}

const searchPlaceholder = computed(() => {
  if (activeModule.value === 'pause') return '搜索暂停供货供应商'
  return '搜索供应商编号 / 名称 / 电话'
})

const emptyTip = computed(() => {
  if (activeModule.value === 'pause') return '没有暂停供货的供应商 — 名录中全部为合格状态'
  return '暂无供应商'
})

// 数据 (共享一份 base_supplier; 评审/证照/绩效走独立记录表)
const query = reactive({ pageNum: 1, pageSize: 50, keyword: '' })
const keyword = ref('')
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const dialogVisible = ref(false)
const supplierOptions = ref([])
const form = reactive({
  id: null, supplierCode: '', supplierName: '',
  isOutsource: 0, contactPerson: '', phone: '', address: '',
  supplierType: 'NORMAL', taxRate: 13.00, status: 1, remark: '',
  foodCertNo: '', foodCertExp: null, foodCertType: null
})

// 名录统计 (后端全量汇总)
const stats = ref({ total: 0, qualified: 0, suspended: 0, dueReview: 0, withCert: 0, rated: 0, warnDays: 90 })
// 到期看板 (证照临期 / 复评到期 / 评级分布 / 最新评审与评级)
const dash = ref({
  warnDays: 90, certDueCount: 0, reviewDueCount: 0,
  certs: [], reviews: [], ratingDistribution: { A: 0, B: 0, C: 0 },
  latestRating: [], latestReview: {}
})
// 暂停/恢复台账 (最近 500 条, 用于暂停视图展示原因与操作人)
const statusLogs = ref([])

function num(v) { return Number(v) || 0 }
function countOf(key) {
  const s = stats.value
  if (key === 'review') return num(s.dueReview)
  if (key === 'docs') return num(s.withCert)
  if (key === 'rate') return num(s.rated)
  if (key === 'pause') return num(s.suspended)
  return num(s.total)
}

const ratingDist = computed(() => dash.value.ratingDistribution || { A: 0, B: 0, C: 0 })

// supplierId → 最新绩效 / 最新评审
const ratingMap = computed(() => {
  const m = {}
  for (const r of dash.value.latestRating || []) m[String(r.supplierId)] = r
  return m
})
function ratingOf(row) { return row ? ratingMap.value[String(row.id)] || null : null }
function reviewOf(row) { return row ? (dash.value.latestReview || {})[String(row.id)] || null : null }
function gradeType(g) { return g === 'A' ? 'success' : (g === 'B' ? 'primary' : 'danger') }

function daysLeftOf(dateStr) { return dateStr ? (new Date(dateStr) - new Date()) / 86400000 : null }
function certExpiringSoon(row) {
  const d = daysLeftOf(row && row.foodCertExp)
  return d !== null && d <= 90
}
function reviewDueSoon(row) {
  const rv = reviewOf(row)
  if (!rv) return true
  const d = daysLeftOf(rv.nextReviewDate)
  return d !== null && d <= 90
}

// 每个供应商取最新一条 SUSPEND 台账 (接口按 oper_time 倒序)
const suspendLogMap = computed(() => {
  const m = {}
  for (const l of statusLogs.value) {
    if (l.action !== 'SUSPEND') continue
    const k = String(l.supplierId)
    if (!m[k]) m[k] = l
  }
  return m
})
function suspendLogOf(row) { return row ? suspendLogMap.value[String(row.id)] || null : null }

const typeMap = { NORMAL: '通用原料', MANUFACTURER: '自产供应商', AGENT: '经销代理商' }
function typeLabel(t) { return typeMap[t] || '通用原料' }

const filteredRows = computed(() => {
  if (activeModule.value === 'pause') return rows.value.filter(r => r.status !== 1)
  return rows.value
})

async function loadData() {
  loading.value = true
  try {
    query.keyword = keyword.value
    const r = await supplierApi.page(query)
    const d = r?.data || {}
    rows.value = d.records || []
    total.value = Number(d.total) || rows.value.length
  } catch (e) {
    console.warn('[Material] load fail:', e)
    rows.value = []
    total.value = 0
  } finally { loading.value = false }
}

async function loadSupplierOptions() {
  try {
    const r = await supplierApi.page({ pageNum: 1, pageSize: 500, keyword: '' })
    supplierOptions.value = r?.data?.records || []
  } catch (e) {
    console.warn('[Material] supplier options fail:', e)
    supplierOptions.value = []
  }
}

async function loadStats() {
  try {
    const d = (await xkSupplierApi.stats())?.data || {}
    stats.value = {
      total: num(d.total),
      qualified: num(d.qualified),
      suspended: num(d.suspended),
      dueReview: num(d.dueReview),
      withCert: num(d.withCert),
      rated: num(d.rated),
      warnDays: num(d.warnDays) || 90
    }
  } catch (e) {
    console.warn('[Material] stats fail:', e)
  }
}

async function loadDashboard() {
  try {
    const d = (await xkSupplierApi.dashboard())?.data || {}
    dash.value = {
      warnDays: num(d.warnDays) || 90,
      certDueCount: num(d.certDueCount),
      reviewDueCount: num(d.reviewDueCount),
      certs: Array.isArray(d.certs) ? d.certs.map(c => ({ ...c, daysLeft: Number(c.daysLeft) || 0 })) : [],
      reviews: Array.isArray(d.reviews) ? d.reviews : [],
      ratingDistribution: d.ratingDistribution || { A: 0, B: 0, C: 0 },
      latestRating: Array.isArray(d.latestRating) ? d.latestRating : [],
      latestReview: d.latestReview || {}
    }
  } catch (e) {
    console.warn('[Material] dashboard fail:', e)
  }
}

async function loadLogs() {
  try {
    statusLogs.value = (await xkSupplierApi.statusLogs({ limit: 500 }))?.data || []
  } catch (e) {
    console.warn('[Material] status-log fail:', e)
    statusLogs.value = []
  }
}

async function refreshAll() {
  const tasks = [loadStats(), loadDashboard(), loadLogs(), loadSupplierOptions()]
  if (isSupplierView.value) tasks.push(loadData())
  await Promise.all(tasks)
}

// v1.1.112 (R20): el-pagination handler 必须显式接参, 否则 pageNum 不更新, 点第 2 页无响应
function queryOnPageChange(p) { if (p === query.pageNum) return; query.pageNum = p; loadData() }
function onSearch() { query.pageNum = 1; loadData() }

function onExport() {
  const list = filteredRows.value
  const headers = ['编码', '名称', '供货范围', '联系人', '电话', '准入状态', '食品资质编号', '资质有效期', '年度评级', '下次复评', '备注']
  const data = list.map(r => {
    const rate = ratingOf(r), rv = reviewOf(r)
    return [
      r.supplierCode || '',
      r.supplierName || '',
      typeLabel(r.supplierType),
      r.contactPerson || '',
      r.phone || '',
      r.status === 1 ? '合格' : '暂停供货',
      r.foodCertNo || '—',
      r.foodCertExp || '—',
      rate ? `${rate.grade} 级 (${num(rate.totalScore)} 分)` : '未评级',
      rv ? (rv.nextReviewDate || '未排期') : '从未评审',
      (r.remark || '').replace(/[\t\r\n]+/g, ' ')
    ]
  })
  const tsv = [headers, ...data].map(row => row.join('\t')).join('\n')
  const blob = new Blob(['\ufeff' + tsv], { type: 'text/tab-separated-values;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${activeLabel.value}-${new Date().toISOString().slice(0, 10)}.tsv`
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${list.length} 条 (${activeLabel.value})`)
}

function todayStr() {
  const d = new Date(), p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function onAdd() {
  Object.assign(form, {
    id: null, supplierCode: '', supplierName: '', supplierType: 'NORMAL', status: 1,
    taxRate: 13.00, isOutsource: 0, remark: '', contactPerson: '', phone: '', taxNo: '', address: '',
    foodCertNo: '', foodCertExp: null, foodCertType: null
  })
  dialogVisible.value = true
}
function onEdit(row) { Object.assign(form, { ...row }); dialogVisible.value = true }
async function onDelete(row) {
  try { await ElMessageBox.confirm(`删除 ${row.supplierName}?`, '提示', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) } catch (e) { return }
  try { await supplierApi.delete(row.id); ElMessage.success('已删除'); refreshAll() }
  catch (e) { ElMessage.error(e.message || '删除失败') }
}
async function onSave() {
  try {
    if (form.id) await supplierApi.update(form); else await supplierApi.add(form)
    ElMessage.success('已保存'); dialogVisible.value = false; refreshAll()
  } catch (e) { ElMessage.error(e.message || '保存失败') }
}

// ====== 暂停供货 (原因必填 + 落台账) ======
const suspendVisible = ref(false)
const suspendRow = ref(null)
const suspendSaving = ref(false)
const suspendUploading = ref(false)
const suspendForm = reactive({ reasonType: 'CERT_EXPIRED', reason: '', effectiveDate: '', evidenceUrl: '' })
const suspendReasonTypes = {
  CERT_EXPIRED: '资质过期 / 未按期换证',
  TEST_FAILED: '检测不合格 (GB 4806 迁移量等)',
  QUALITY_ISSUE: '供货质量异常 / 批次退货',
  UNNOTIFIED_CHANGE: '擅自变更配方或工艺',
  FOOD_SAFETY_EVENT: '食品安全事件 / 召回',
  RATING_C: '年度评价 C 级',
  OTHER: '其他'
}
function reasonTypeLabel(t) { return suspendReasonTypes[t] || t || '其他' }

function openSuspend(row) {
  suspendRow.value = row
  Object.assign(suspendForm, { reasonType: 'CERT_EXPIRED', reason: '', effectiveDate: todayStr(), evidenceUrl: '' })
  suspendVisible.value = true
}

async function onSuspendUpload(req) {
  suspendUploading.value = true
  try {
    suspendForm.evidenceUrl = await uploadFile(req.file)
    ElMessage.success('附件已上传')
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally { suspendUploading.value = false }
}

async function submitSuspend() {
  if (!suspendRow.value) return
  if (!suspendForm.reason || !suspendForm.reason.trim()) {
    ElMessage.warning('请填写暂停原因说明')
    return
  }
  suspendSaving.value = true
  try {
    await xkSupplierApi.suspend(suspendRow.value.id, { ...suspendForm })
    ElMessage.success(`已暂停 ${suspendRow.value.supplierName} 供货`)
    suspendVisible.value = false
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '暂停失败')
  } finally { suspendSaving.value = false }
}

async function onResume(row) {
  let reason = ''
  try {
    const r = await ElMessageBox.prompt(
      `恢复 ${row.supplierName} 为合格供应商前, 请填写恢复理由 (整改完成 / 复评结论)。恢复后即可创建采购订单。`,
      '恢复供货',
      {
        confirmButtonText: '恢复',
        cancelButtonText: '取消',
        inputPlaceholder: '如: 已提交整改报告, 2026-11 复评通过',
        inputValidator: v => (v && v.trim() ? true : '请填写恢复理由')
      }
    )
    reason = r.value
  } catch (e) { return }
  try {
    await xkSupplierApi.resume(row.id, { reason, effectiveDate: todayStr() })
    ElMessage.success(`已恢复 ${row.supplierName}`)
    await refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  }
}

function onLog() { router.push('/xk/audit') }

onMounted(refreshAll)
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

// 5 项模块入口
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

// XK 到期看板
.board-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
@media (max-width: 900px) { .board-grid { grid-template-columns: 1fr; } }
.board-tile {
  position: relative; overflow: hidden;
  padding: 14px 16px; border: 1px solid #eef2f7; border-radius: 11px;
  background: #fbfcfe; display: flex; flex-direction: column; gap: 3px;
}
.board-tile::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 4px; }
.board-tile.bt-amber::before { background: #e6a23c; }
.board-tile.bt-red::before { background: #d63b3b; }
.board-tile.bt-green::before { background: #1f8a4c; }
.bt-label { font-size: 12px; color: var(--muted); }
.bt-num { font-size: 24px; font-weight: 800; color: var(--text); line-height: 1.15; }
.bt-num small { font-size: 12px; color: var(--muted); font-weight: 500; margin-left: 4px; }
.bt-note { font-size: 11px; color: var(--muted); }

.board-warn {
  display: flex; align-items: flex-start; gap: 10px;
  margin-top: 12px; padding: 10px 14px;
  background: #fff8ee; border: 1px solid #f5d8ae; border-radius: 10px;
  &.bw-review { background: #fff3f3; border-color: #f5cccc; .bw-icon { color: #d63b3b; } }
}
.board-warn .bw-icon { color: #d96a2b; margin-top: 2px; flex: 0 0 auto; }
.board-warn .bw-body { min-width: 0; }
.board-warn .bw-body b { display: block; font-size: 12px; color: var(--text); }
.board-warn .bw-body small { display: block; font-size: 11px; color: var(--muted); margin-top: 2px; line-height: 1.5; }

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
  a { color: var(--primary-color); }
}

// 暂停弹窗: 目标供应商
.suspend-target {
  display: flex; align-items: baseline; gap: 10px;
  padding: 10px 14px; margin-bottom: 14px;
  background: #fff7f7; border: 1px solid #f5d3d3; border-radius: 9px;
}
.suspend-target b { font-size: 14px; font-weight: 730; color: #a12727; }

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
