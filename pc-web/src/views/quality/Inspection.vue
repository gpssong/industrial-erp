<template>
  <div>
    <!-- Figma 页面标题 -->
    <div class="xk-page-heading">
      <div>
        <div class="xk-eyebrow">生产与质量 / 三级检验</div>
        <h1 class="xk-title">质量检验 (IQC / FAI / PAT / OQC)</h1>
      </div>
      <div class="xk-heading-actions">
        <el-button type="primary" @click="openAdd">新增检验单</el-button>
      </div>
    </div>
    <div class="page-card">
      <!-- 工具栏 + 筛选 -->
      <div class="toolbar" style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div class="section-title">原料 · 半成品 · 成品 检验合一台账</div>
      </div>

      <el-form :model="query" inline style="margin-bottom:12px">
        <el-form-item label="类型">
          <el-select v-model="query.type" clearable placeholder="全部" style="width:160px">
            <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="query.result" clearable placeholder="全部" style="width:130px">
            <el-option label="合格" value="PASS" />
            <el-option label="不合格" value="FAIL" />
            <el-option label="冻结" value="HOLD" />
            <el-option label="放行处置" value="PASS_DISPOSITION" />
            <el-option label="拒收" value="FAIL_REJECT" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width:120px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="已审核" value="CHECKED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column type="index" width="50" />
        <el-table-column prop="billNo" label="单号" width="150">
          <template #default="{ row }">
            <el-tooltip :content="'复制: ' + row.billNo" placement="top">
              <span @click="copy(row.billNo)" style="cursor:pointer;color:#409eff">{{ row.billNo }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="typeTag(row.inspectionType)" size="small">{{ typeLabel(row.inspectionType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="产品" min-width="150">
          <template #default="{ row }">
            {{ row.productName || '-' }}<span class="muted-tip" v-if="row.spec"> / {{ row.spec }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="batchNo" label="批次" width="130">
          <template #default="{ row }">{{ row.batchNo || '-' }}</template>
        </el-table-column>
        <el-table-column label="来源单号" width="150">
          <template #default="{ row }">
            <span v-if="row.sourceBillType === 'PRD_PROCESS'">
              <el-tag size="small" type="primary">工序巡检</el-tag>
              <span style="margin-left:4px">{{ row.sourceBillNo || '-' }}</span>
            </span>
            <span v-else>{{ row.sourceBillNo || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="车间" width="110">
          <template #default="{ row }">{{ row.workshop || '-' }}</template>
        </el-table-column>
        <el-table-column label="供应商/客户" width="120">
          <template #default="{ row }">
            <span v-if="row.supplierId">供 {{ row.supplierId }}</span>
            <span v-else-if="row.customerId">客 {{ row.customerId }}</span>
            <span v-else class="muted-tip">-</span>
          </template>
        </el-table-column>
        <el-table-column label="抽检/合格/不良" width="150" align="center">
          <template #default="{ row }">
            {{ num(row.sampleQty) }} / {{ num(row.qualifiedQty) }} /
            <span style="color:#f56c6c">{{ num(row.unqualifiedQty) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.result" :type="resultTag(row.result)" size="small">{{ resultLabel(row.result) }}</el-tag>
            <span v-else class="muted-tip">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'CHECKED' ? 'success' : 'warning'" size="small">
              {{ row.status === 'CHECKED' ? '已审核' : '草稿' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="inspectorName" label="检验人" width="110">
          <template #default="{ row }">{{ row.inspectorName || row.inspector || '-' }}</template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmt(row.billDate || row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openView(row)">查看</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="success" size="small" @click="openCheck(row)">审核</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="warning" size="small" @click="openReject(row)">拒收</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!list.length && !loading" description="暂无检验单 (采购/成品/退货审核后自动生成, 或手动新增)" />
      <el-pagination class="pager" background layout="total, prev, pager, next, jumper"
        :total="Number(total)" :current-page="query.pageNum" :page-size="query.pageSize"
        @current-change="queryOnPageChange" @size-change="queryOnSizeChange" :page-sizes="[10, 20, 50, 100]" />
    </div>

    <!-- 新增 对话框 -->
    <el-dialog v-model="dialogVisible" title="新增检验单" width="820px" destroy-on-close>
      <el-form :model="form" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="检验类型" required>
              <el-select v-model="form.inspectionType" style="width:100%">
                <el-option v-for="t in TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="来源单据号">
              <el-input v-model="form.sourceBillNo" placeholder="如采购入库单号" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-divider content-position="left">产品信息 (简化版, 未来加 base_product 选择器)</el-divider>
        <el-row :gutter="12">
          <el-col :span="6">
            <el-form-item label="产品ID">
              <el-input-number v-model="form.productId" :min="1" :controls="false" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="产品名称"><el-input v-model="form.productName" /></el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="规格"><el-input v-model="form.spec" /></el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="型号"><el-input v-model="form.model" /></el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="6">
            <el-form-item label="批次号"><el-input v-model="form.batchNo" /></el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="抽检数量"><el-input-number v-model="form.sampleQty" :min="0" :controls="false" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="供应商ID">
              <el-input-number v-model="form.supplierId" :controls="false" placeholder="可空" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="客户ID">
              <el-input-number v-model="form.customerId" :controls="false" placeholder="可空" style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="缺陷明细">
          <el-button @click="addDefect" type="primary" plain size="small">添加缺陷行</el-button>
          <el-table :data="form.defects" size="small" border style="margin-top:8px">
            <el-table-column label="序号" width="60" align="center">
              <template #default="{ $index }">{{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="缺陷类型" min-width="140">
              <template #default="{ row }">
                <el-input v-model="row.defectType" size="small" placeholder="划伤/脏污/尺寸超差..." />
              </template>
            </el-table-column>
            <el-table-column label="严重度" width="130">
              <template #default="{ row }">
                <el-select v-model="row.severity" size="small">
                  <el-option label="轻微" value="MINOR" />
                  <el-option label="一般" value="MAJOR" />
                  <el-option label="严重" value="CRITICAL" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="100">
              <template #default="{ row }">
                <el-input-number v-model="row.qty" :min="0" size="small" :controls="false" style="width:100%" />
              </template>
            </el-table-column>
            <el-table-column label="位置" min-width="120">
              <template #default="{ row }"><el-input v-model="row.location" size="small" placeholder="如 卷头/膜面" /></template>
            </el-table-column>
            <el-table-column label="处置" min-width="130">
              <template #default="{ row }">
                <el-select v-model="row.disposal" size="small">
                  <el-option label="报废" value="SCRAP" />
                  <el-option label="返工" value="REWORK" />
                  <el-option label="让步接收" value="CONCESSION" />
                  <el-option label="分拣" value="SORT" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="60" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" size="small" @click="form.defects.splice($index, 1)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 查看详情 对话框 (readonly) -->
    <el-dialog v-model="viewVisible" title="检验单详情" width="760px" destroy-on-close>
      <el-descriptions :column="2" border v-if="view" style="margin-bottom:12px">
        <el-descriptions-item label="单号">{{ view.billNo }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ typeLabel(view.inspectionType) }}</el-descriptions-item>
        <el-descriptions-item label="产品名称">{{ view.productName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="规格/型号">{{ [view.spec, view.model].filter(Boolean).join(' / ') || '-' }}</el-descriptions-item>
        <el-descriptions-item label="批次">{{ view.batchNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源单据">{{ view.sourceBillNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="车间">{{ view.workshop || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源类型">{{ sourceLabel(view.sourceBillType) }}</el-descriptions-item>
        <el-descriptions-item label="抽检数量">{{ num(view.sampleQty) }}</el-descriptions-item>
        <el-descriptions-item label="合格/不良">{{ num(view.qualifiedQty) }} / {{ num(view.unqualifiedQty) }}</el-descriptions-item>
        <el-descriptions-item label="结果">{{ view.result ? resultLabel(view.result) : '-' }}</el-descriptions-item>
        <el-descriptions-item label="处置">{{ view.disposition || '-' }}</el-descriptions-item>
        <el-descriptions-item label="检验人">{{ view.inspectorName || view.inspector || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ view.status === 'CHECKED' ? '已审核' : '草稿' }}</el-descriptions-item>
        <!-- v1.1.100 OQC 3 签 + 留样 (仅 OQC 有值时展示) -->
        <template v-if="view.inspectionType === 'OQC' && (view.reviewerName || view.approverName || view.retainNo)">
          <el-descriptions-item label="审核人">{{ view.reviewerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="批准人">{{ view.approverName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="留样编号">{{ view.retainNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="留样位置/期限">{{ view.retainLoc ? (view.retainLoc + ' · ') : '' }}{{ view.retainDueDate || '-' }}</el-descriptions-item>
        </template>
        <el-descriptions-item label="处置原因" :span="2">{{ view.dispositionReason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ view.remark || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="viewDefects" size="small" border>
        <el-table-column label="序号" width="60" align="center">
          <template #default="{ $index }">{{ $index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="defectType" label="缺陷类型" min-width="140" />
        <el-table-column label="严重度" width="100">
          <template #default="{ row }">{{ { MINOR: '轻微', MAJOR: '一般', CRITICAL: '严重' }[row.severity] || row.severity || '-' }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="80" align="right" />
        <el-table-column prop="location" label="位置" min-width="120" />
        <el-table-column label="处置" min-width="120">
          <template #default="{ row }">{{ { SCRAP: '报废', REWORK: '返工', CONCESSION: '让步接收', SORT: '分拣' }[row.disposal] || row.disposal || '-' }}</template>
        </el-table-column>
      </el-table>
      <!-- v1.1.131 检验项模板 (服务器固化定义) 只读展示: 出厂/工序检验逐检验项留痕 -->
      <template v-if="viewItems.length">
        <div class="section-title" style="margin:14px 0 8px">检验项 ({{ viewItems.length }} 项)</div>
        <el-table :data="viewItems" size="small" border>
          <el-table-column prop="lineNo" label="#" width="46" align="center" />
          <el-table-column prop="itemName" label="检验项" min-width="130" />
          <el-table-column prop="requirement" label="检验要求 (按适用产品标准/检验规程)" min-width="240" />
          <el-table-column prop="method" label="方法" min-width="120" />
          <el-table-column prop="unit" label="单位" width="80" />
          <el-table-column prop="resultValue" label="实测/观察" min-width="120">
            <template #default="{ row }">{{ row.resultValue || '未检' }}</template>
          </el-table-column>
          <el-table-column label="判定" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="judgeTag(row.passFlag)">{{ judgeLabel(row.passFlag) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="食品级" width="80" align="center">
            <template #default="{ row }">{{ Number(row.isFoodGradeItem) === 1 ? '是' : '-' }}</template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer><el-button @click="viewVisible = false">关闭</el-button></template>
    </el-dialog>

    <!-- 审核对话框 -->
    <el-dialog v-model="checkVisible" :title="checkTitle" :width="checkItems.length ? '940px' : '480px'" destroy-on-close>
      <!-- v1.1.131 检验项逐项目录 (成品检 OQC/OQC_RETURN、工序检 FAI/PAT 由服务器模板固化, 只能回填不能删项) -->
      <template v-if="checkItems.length">
        <div class="items-head">
          <span class="section-title">检验项 ({{ checkItems.length }} 项，已判定 {{ judgedCount }})</span>
          <el-button size="small" :loading="itemsSaving" @click="onSaveItems">保存检验项</el-button>
        </div>
        <el-table :data="checkItems" size="small" border max-height="320" style="margin-bottom:12px">
          <el-table-column prop="lineNo" label="#" width="46" align="center" />
          <el-table-column prop="itemName" label="检验项" min-width="120" />
          <el-table-column prop="requirement" label="检验要求 (按适用产品标准/检验规程)" min-width="230" />
          <el-table-column prop="method" label="方法" min-width="110" />
          <el-table-column label="实测/观察值" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.resultValue" size="small" :disabled="itemLocked"
                :placeholder="row.unit ? ('如 ' + row.unit) : '记录实测或观察结果'" />
            </template>
          </el-table-column>
          <el-table-column label="判定" width="120" align="center">
            <template #default="{ row }">
              <el-select v-model="row.passFlag" size="small" :disabled="itemLocked">
                <el-option label="待检" :value="-1" />
                <el-option label="合格" :value="1" />
                <el-option label="不合格" :value="0" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="判定依据/报告号" min-width="150">
            <template #default="{ row }">
              <el-input v-model="row.judgmentBasis" size="small" :disabled="itemLocked" placeholder="标准条款/报告号" />
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-form :model="checkForm" label-width="90px">
        <el-form-item label="检验结果" required>
          <el-select v-model="checkForm.result" style="width:100%">
            <el-option label="合格" value="PASS" />
            <el-option label="合格(处置)" value="PASS_DISPOSITION" />
            <el-option label="不合格" value="FAIL" />
            <el-option label="冻结" value="HOLD" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置">
          <el-select v-model="checkForm.disposition" clearable style="width:100%">
            <el-option label="无" value="NONE" />
            <el-option label="冻结批次" value="FROZEN" />
            <el-option label="报废" value="SCRAP" />
            <el-option label="返工" value="REWORK" />
            <el-option label="让步接收" value="CONCESSION" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因/说明">
          <el-input v-model="checkForm.reason" type="textarea" :rows="3" placeholder="审核意见 / 不良原因" />
        </el-form-item>
        <!-- v1.1.131 成品检验数量留痕 (XK16-204 要求记录检验数量与合格数量) -->
        <template v-if="showQty">
          <el-form-item label="抽检数量">
            <el-input-number v-model="checkForm.sampleQty" :min="0" :disabled="itemLocked" :controls="false" style="width:100%" />
          </el-form-item>
          <el-form-item label="合格数量">
            <el-input-number v-model="checkForm.qualifiedQty" :min="0" :disabled="itemLocked" :controls="false" style="width:100%" />
          </el-form-item>
          <el-form-item label="不良数量">
            <el-input-number v-model="checkForm.unqualifiedQty" :min="0" :disabled="itemLocked" :controls="false" style="width:100%" />
          </el-form-item>
        </template>
        <!-- v1.1.100 OQC 成品放行 3 签 + 留样 (仅 OQC + 合格结果展开; 缺则后端按默认回填) -->
        <template v-if="isOqcPass">
          <el-divider content-position="left">OQC 放行 3 签 + 留样</el-divider>
          <el-form-item label="检验人"><el-input :model-value="checkForm.inspectorName || currentInspector" readonly placeholder="当前登录用户" /></el-form-item>
          <el-form-item label="审核人"><el-input v-model="checkForm.reviewerName" placeholder="审核人姓名 (可空)" /></el-form-item>
          <el-form-item label="批准人"><el-input v-model="checkForm.approverName" placeholder="批准人姓名 (可空)" /></el-form-item>
          <el-form-item label="留样编号"><el-input v-model="checkForm.retainNo" placeholder="缺则按 LY-{单号}" /></el-form-item>
          <el-form-item label="留样位置"><el-input v-model="checkForm.retainLoc" placeholder="如 质量室/留样柜 A-06" /></el-form-item>
          <el-form-item label="留样期限"><el-date-picker v-model="checkForm.retainDueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" placeholder="缺则 +24 月" /></el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="checkVisible = false">取消</el-button>
        <el-button type="primary" :loading="checkLoading" @click="onCheck">确认审核</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inspectionApi } from '@/api/quality'

const TYPE_OPTIONS = [
  { value: 'IQC', label: 'IQC 来料' },
  { value: 'FAI', label: 'FAI 首件' },
  { value: 'PAT', label: 'PAT 过程' },
  { value: 'OQC', label: 'OQC 出货' },
  { value: 'OQC_RETURN', label: 'OQC_RETURN 退货' }
]

const SEV_LABEL = { MINOR: '轻微', MAJOR: '一般', CRITICAL: '严重' }
const DISP_LABEL = { NONE: '无', FROZEN: '冻结批次', SCRAP: '报废', REWORK: '返工', SORT: '分拣', CONCESSION: '让步接收' }

const loading = ref(false)
const saving = ref(false)
const checkLoading = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 20, type: '', result: '', status: '' })

// 新增 form (head 字段 + defects)
const dialogVisible = ref(false)
const emptyForm = () => ({
  inspectionType: '', sourceBillNo: '', productId: null, productName: '', spec: '', model: '',
  batchNo: '', supplierId: null, customerId: null, sampleQty: 0, remark: '', defects: []
})
const form = reactive(emptyForm())

// 查看
const viewVisible = ref(false)
const view = ref(null)
const viewDefects = ref([])
// v1.1.131 — 检验项 (服务器模板固化): 详情只读展示 + 审核面板回填实测值/判定
const viewItems = ref([])
const checkItems = ref([])
const itemsSaving = ref(false)
const TEMPLATE_TYPES = ['FAI', 'PAT', 'OQC', 'OQC_RETURN']
const itemLocked = computed(() => checkRow.value?.status === 'CHECKED')
// 数量录入区仅成品检 (OQC/OQC_RETURN) 展开: 工序检数量由 App 报工链路写入, PC 不回改
const showQty = computed(() => checkItems.value.length
  && ['OQC', 'OQC_RETURN'].includes(checkRow.value?.inspectionType))
const judgedCount = computed(() =>
  checkItems.value.filter(it => Number(it.passFlag) === 1 || Number(it.passFlag) === 0).length)
const checkTitle = computed(() => checkRow.value
  ? `审核检验单 ${checkRow.value.billNo || ''} (${typeLabel(checkRow.value.inspectionType)})`
  : '审核检验单')

function judgeLabel(f) { return Number(f) === 1 ? '合格' : Number(f) === 0 ? '不合格' : '待检' }
function judgeTag(f) { return Number(f) === 1 ? 'success' : Number(f) === 0 ? 'danger' : 'info' }

// 审核
const checkVisible = ref(false)
const checkRow = ref(null)
const checkForm = reactive({
  result: 'PASS', disposition: '', reason: '',
  // v1.1.100 OQC 3 签 + 留样 (仅 OQC 合格结果时透传给后端回填)
  reviewerName: '', approverName: '', retainNo: '', retainLoc: '', retainDueDate: null,
  // v1.1.131 检验数量留痕 (仅模板检验单面板展开, 随审核一起提交)
  sampleQty: 0, qualifiedQty: 0, unqualifiedQty: 0
})
// OQC 放行 (PASS/PASS_DISPOSITION) 才展开 3 签/留样区
const isOqcPass = computed(() =>
  checkRow.value?.inspectionType === 'OQC'
  && (checkForm.result === 'PASS' || checkForm.result === 'PASS_DISPOSITION'))
const currentInspector = ref('')

function typeTag(t) {
  return { IQC: 'info', FAI: 'warning', PAT: 'primary', OQC: 'success', OQC_RETURN: 'danger' }[t] || 'info'
}
function typeLabel(t) {
  const found = TYPE_OPTIONS.find(x => x.value === t)
  return found ? found.label : (t || '-')
}
// v1.1.x PAT 补齐: 来源单据类型中文 (PRD_PROCESS=工序巡检报工)
function sourceLabel(t) {
  return {
    PUR_RECEIPT: '采购入库', PRD_PROCESS: '工序巡检', PRD_SEMI_IN: '半成品入库',
    PRD_FINISHED_IN: '成品入库', SAL_RETURN: '销售退货', SAL_DELIVERY: '销售出库',
    MANUAL: '手工录入'
  }[t] || (t || '-')
}
function resultTag(r) {
  return { PASS: 'success', PASS_DISPOSITION: 'success', FAIL: 'danger', HOLD: 'warning', FAIL_REJECT: 'danger' }[r] || 'info'
}
function resultLabel(r) {
  return { PASS: '合格', PASS_DISPOSITION: '合格(处置)', FAIL: '不合格', HOLD: '冻结', FAIL_REJECT: '拒收' }[r] || r
}
function num(v) { return v == null ? 0 : Number(v) }
function fmt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '-' }

function copy(text) {
  if (!text) return
  const el = document.createElement('textarea')
  el.value = text
  document.body.appendChild(el)
  el.select()
  try { document.execCommand('copy'); ElMessage.success('已复制 ' + text) }
  catch (e) { ElMessage.error('复制失败') }
  document.body.removeChild(el)
}

// 后端 page 参数: inspectionType / result / status
function buildParams() {
  const p = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.type) p.inspectionType = query.type
  if (query.result) p.result = query.result
  if (query.status) p.status = query.status
  return p
}

async function load() {
  loading.value = true
  try {
    const r = await inspectionApi.page(buildParams())
    list.value = r?.data?.records || []
    total.value = Number(r?.data?.total || 0)
  } catch (e) {
    // 与 OrderProgress.vue 一致, 列表加载失败静默兜底, 不阻塞页内其他区块
    console.warn('[Inspection] 列表加载失败:', e)
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
// v1.1.112 (R20): el-pagination handler 必须显式接参, 否则 pageNum 不更新, 点第 2 页无响应
function queryOnPageChange(p) { if (p === query.pageNum) return; query.pageNum = p; load() }
function queryOnSizeChange(s) { if (s === query.pageSize) return; query.pageSize = s; query.pageNum = 1; load() }

function onReset() {
  query.type = ''; query.result = ''; query.status = ''; query.pageNum = 1
  load()
}

function addDefect() {
  form.defects.push({ defectType: '', severity: 'MINOR', qty: 1, location: '', disposal: 'SCRAP' })
}

function openAdd() {
  Object.assign(form, emptyForm())
  form.defects = []
  dialogVisible.value = true
}

async function openView(row) {
  // 先乐观填充 head 字段, 再拉详情补齐 defects
  view.value = { ...row }
  viewDefects.value = []
  viewItems.value = []
  viewVisible.value = true
  try {
    const r = await inspectionApi.detail(row.id)
    const head = r?.data?.head
    if (head) view.value = head
    viewDefects.value = (r?.data?.defects || []).map(x => ({ ...x }))
    viewItems.value = (r?.data?.items || []).map(x => ({ ...x }))
    if (!viewItems.value.length) {
      const ir = await inspectionApi.iqcItems(row.id)
      viewItems.value = (ir?.data || []).map(x => ({ ...x }))
    }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '加载详情失败')
  }
}

async function onSave() {
  if (!form.inspectionType) { ElMessage.warning('检验类型必填'); return }
  if (!form.productId) { ElMessage.warning('产品ID必填'); return }
  saving.value = true
  try {
    const head = { ...form }
    // create 走 InspectionCreateDTO { head, defects }
    delete head.defects
    const payload = { head, defects: form.defects.map(d => ({ ...d })) }
    await inspectionApi.create(payload)
    ElMessage.success('已保存草稿')
    dialogVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存失败')
  } finally {
    saving.value = false
  }
}

async function openCheck(row) {
  checkRow.value = row
  currentInspector.value = row.inspectorName || row.inspector || ''
  checkItems.value = []
  Object.assign(checkForm, {
    result: 'PASS', disposition: '', reason: '',
    reviewerName: '', approverName: '', retainNo: '', retainLoc: '', retainDueDate: null,
    sampleQty: num(row.sampleQty), qualifiedQty: num(row.qualifiedQty), unqualifiedQty: num(row.unqualifiedQty)
  })
  checkVisible.value = true
  if (!TEMPLATE_TYPES.includes(row.inspectionType)) return
  try {
    const r = await inspectionApi.ensureTemplate(row.id)
    checkItems.value = (r?.data?.items || []).map(x => ({ ...x }))
    if (r?.data?.head) checkRow.value = { ...row, ...r.data.head }
    if (!checkItems.value.length) {
      ElMessage.warning('该检验单未取到模板检验项，请确认产品/工序信息完整后重试')
    }
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '加载检验项模板失败')
  }
}

/** v1.1.131 — 回填检验项: 整表提交 (后端只认服务器模板项, 删项/改定义/手工替代一律拒绝). */
async function saveItems(silent = false) {
  if (!checkItems.value.length || itemLocked.value) return true
  const pending = checkItems.value.filter(it => Number(it.passFlag) !== 1 && Number(it.passFlag) !== 0)
  if (pending.length && checkForm.result !== 'HOLD') {
    if (!silent) ElMessage.warning(`还有 ${pending.length} 项未判定: ${pending[0].itemName} 等`)
    if (!silent) return false
  }
  const noValue = checkItems.value.filter(it => Number(it.passFlag) === 1 || Number(it.passFlag) === 0)
    .filter(it => !String(it.resultValue || '').trim())
  if (noValue.length) {
    if (!silent) ElMessage.warning(`判定项缺少实测/观察记录: ${noValue[0].itemName}`)
    return false
  }
  itemsSaving.value = true
  try {
    await inspectionApi.iqcSaveItems(checkRow.value.id, checkItems.value.map(it => ({ ...it })))
    if (!silent) ElMessage.success('检验项已保存')
    return true
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '保存检验项失败')
    return false
  } finally {
    itemsSaving.value = false
  }
}

async function onSaveItems() {
  if (await saveItems()) load()
}

async function onCheck() {
  if (!checkForm.result) { ElMessage.warning('请选择检验结果'); return }
  if (checkItems.value.length && !await saveItems()) return
  checkLoading.value = true
  try {
    // 后端 /{id}/check 收 Map<String,String>: 数量统一转字符串, 空值不提交 (沿用单据原值)
    const qty = {}
    if (showQty.value) {
      if (checkForm.sampleQty != null && checkForm.sampleQty !== '') qty.sampleQty = String(checkForm.sampleQty)
      if (checkForm.qualifiedQty != null && checkForm.qualifiedQty !== '') qty.qualifiedQty = String(checkForm.qualifiedQty)
      if (checkForm.unqualifiedQty != null && checkForm.unqualifiedQty !== '') qty.unqualifiedQty = String(checkForm.unqualifiedQty)
    }
    await inspectionApi.check(checkRow.value.id, { ...checkForm, ...qty })
    ElMessage.success('审核成功')
    checkVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e && (e.msg || e.message)) || '审核失败')
  } finally {
    checkLoading.value = false
  }
}

function openReject(row) {
  ElMessageBox.prompt('填写拒收原因', `拒收检验单 ${row.billNo}`, {
    confirmButtonText: '拒收',
    cancelButtonText: '取消',
    inputType: 'textarea',
    inputPlaceholder: '拒收原因 (必填)'
  }).then(async ({ value }) => {
    if (!value || !value.trim()) { ElMessage.warning('请填写拒收原因'); return }
    try {
      await inspectionApi.reject(row.id, value.trim())
      ElMessage.success('已拒收 (FAIL_REJECT + 报废)')
      load()
    } catch (e) {
      ElMessage.error((e && (e.msg || e.message)) || '拒收失败')
    }
  }).catch(() => {})
}

onMounted(load)
</script>

<style scoped>
.section-title { font-size:15px;font-weight:600;color:#333 }
.muted-tip { color:#909399;font-size:12px }
.pager { margin-top:12px; text-align:right; }
.items-head { display:flex;align-items:center;justify-content:space-between;margin-bottom:8px }
</style>
